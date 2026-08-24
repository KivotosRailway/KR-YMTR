package mtr.client;

import com.mojang.blaze3d.audio.OggAudioStream;
import mtr.mappings.Text;
import net.minecraft.network.chat.Component;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.PushbackInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public class NetworkAudioPlayer {

	public enum Status {
		DOWNLOADING, PLAYING, FAILED, FINISHED
	}

	@FunctionalInterface
	public interface StatusCallback {
		void onStatus(Status status, Component message);
	}

	private static final ExecutorService AUDIO_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
		final Thread thread = new Thread(r, "MTR-NetworkAudio");
		thread.setDaemon(true);
		return thread;
	});

	private static final int MAX_CACHE_SIZE = 20;
	private static final int MAX_CACHE_FILE_SIZE = 2 * 1024 * 1024;
	private static final Map<String, byte[]> AUDIO_CACHE = Collections.synchronizedMap(new LinkedHashMap<String, byte[]>(MAX_CACHE_SIZE + 1, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
			return size() > MAX_CACHE_SIZE;
		}
	});

	private static final int CONNECT_TIMEOUT_MS = 10000;
	private static final int READ_TIMEOUT_MS = 30000;
	private static final int MAX_RETRIES = 2;
	private static final int MAX_REDIRECTS = 5;
	private static final int IO_BUFFER_SIZE = 8192;
	private static final long STREAM_BUFFER_LIMIT_BYTES = 4 * 1024 * 1024;
	private static final Object EOF_MARKER = new Object();

	private static volatile PlayTask currentTask;

	public static void playAsync(String urlString, StatusCallback callback) {
		final PlayTask previousTask = currentTask;
		if (previousTask != null) {
			if (previousTask.isActive() && previousTask.urlString.equals(urlString)) {
				return;
			}
			previousTask.cancel();
		}
		final PlayTask newTask = new PlayTask(urlString, callback);
		currentTask = newTask;
		AUDIO_EXECUTOR.submit(newTask);
	}

	public static void clearCache() {
		AUDIO_CACHE.clear();
	}

	public static void shutdown() {
		final PlayTask task = currentTask;
		if (task != null) {
			task.cancel();
		}
		AUDIO_EXECUTOR.shutdownNow();
	}

	private static class PlayTask implements Runnable {

		private final String urlString;
		private final StatusCallback callback;
		private volatile boolean cancelled;
		private volatile boolean finished;
		private volatile Thread playThread;
		private volatile HttpURLConnection connection;

		private PlayTask(String urlString, StatusCallback callback) {
			this.urlString = urlString;
			this.callback = callback;
		}

		private boolean isActive() {
			return !cancelled && !finished;
		}

		private void cancel() {
			cancelled = true;
			final HttpURLConnection currentConnection = connection;
			if (currentConnection != null) {
				currentConnection.disconnect();
			}
			final Thread thread = playThread;
			if (thread != null) {
				thread.interrupt();
			}
		}

		@Override
		public void run() {
			playThread = Thread.currentThread();
			try {
				for (int attempt = 0; attempt <= MAX_RETRIES && !cancelled; attempt++) {
					try {
						playOnce();
						return;
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					} catch (InterruptedIOException e) {
						return;
					} catch (Exception e) {
						if (cancelled) {
							return;
						}
						if (attempt < MAX_RETRIES) {
							notifyStatus(Status.FAILED, null);
							System.out.println("[MTR] Network audio: playback failed, retrying (" + (attempt + 1) + "/" + MAX_RETRIES + "): " + urlString + " (" + getErrorMessage(e) + ")");
							try {
								sleepInterruptibly(1500L * (attempt + 1));
							} catch (InterruptedException interruptedException) {
								Thread.currentThread().interrupt();
								return;
							}
						} else {
							notifyStatus(Status.FAILED, Text.translatable("gui.mtr.network_audio_failed", getErrorMessage(e)));
						}
					}
				}
			} finally {
				finished = true;
				if (currentTask == this) {
					currentTask = null;
				}
				notifyStatus(Status.FINISHED, null);
			}
		}

		private void playOnce() throws Exception {
			notifyStatus(Status.DOWNLOADING, null);
			System.out.println("[MTR] Network audio: downloading " + urlString);

			final byte[] cached = AUDIO_CACHE.get(urlString);
			if (cached != null) {
				playDecodedData(cached);
				return;
			}

			final HttpURLConnection httpConnection = openConnectionWithRedirects(URI.create(urlString).toURL());
			connection = httpConnection;
			try {
				final InputStream networkStream = new BufferedInputStream(httpConnection.getInputStream());
				final PushbackInputStream pushbackStream = new PushbackInputStream(networkStream, 4);
				final byte[] magic = new byte[4];
				if (readSome(pushbackStream, magic) < 4) {
					throw new IOException(Text.translatable("gui.mtr.network_audio_invalid").getString());
				}
				pushbackStream.unread(magic);

				if (magic[0] == 'R' && magic[1] == 'I' && magic[2] == 'F' && magic[3] == 'F') {
					playWavStreaming(pushbackStream);
				} else if (magic[0] == 'O' && magic[1] == 'g' && magic[2] == 'g' && magic[3] == 'S') {
					playOggStreaming(pushbackStream);
				} else {
					playDecodedData(downloadAll(pushbackStream));
				}
			} finally {
				httpConnection.disconnect();
			}
		}

		private void playWavStreaming(InputStream networkStream) throws Exception {
			final StreamBuffer streamBuffer = new StreamBuffer(STREAM_BUFFER_LIMIT_BYTES);
			final Thread downloadThread = startDownloadThread(networkStream, streamBuffer);
			try {
				final AudioFormat format = parseWavHeader(streamBuffer);
				playPcmStream(streamBuffer, format);
			} finally {
				downloadThread.interrupt();
			}
		}

		private void playOggStreaming(InputStream networkStream) throws Exception {
			final StreamBuffer streamBuffer = new StreamBuffer(STREAM_BUFFER_LIMIT_BYTES);
			final Thread downloadThread = startDownloadThread(networkStream, streamBuffer);
			try (OggAudioStream audioStream = new OggAudioStream(streamBuffer)) {
				final AudioFormat format = audioStream.getFormat();
				final SourceDataLine line = openLine(format);
				if (line == null) {
					throw new IOException(Text.translatable("gui.mtr.network_audio_unsupported_format").getString());
				}
				try {
					line.start();
					boolean notified = false;
					final byte[] buffer = new byte[IO_BUFFER_SIZE];
					int emptyReadCount = 0;
					while (!cancelled) {
						final ByteBuffer pcmBuffer = audioStream.read(buffer.length);
						if (pcmBuffer == null) {
							break;
						}
						if (!pcmBuffer.hasRemaining()) {
							if (++emptyReadCount >= 2) {
								break;
							}
							continue;
						}
						emptyReadCount = 0;
						final byte[] pcmData = new byte[pcmBuffer.remaining()];
						pcmBuffer.get(pcmData);
						line.write(pcmData, 0, pcmData.length);
						if (!notified) {
							notified = true;
							notifyStatus(Status.PLAYING, null);
							System.out.println("[MTR] Network audio: playing " + urlString);
						}
					}
					if (!cancelled) {
						line.drain();
					}
				} finally {
					line.close();
				}
			} finally {
				downloadThread.interrupt();
			}
		}

		private void playDecodedData(byte[] data) throws Exception {
			try (AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(data))) {
				final AudioFormat format = audioInputStream.getFormat();
				if (isPcmEncoding(format.getEncoding())) {
					playPcmStream(audioInputStream, format);
				} else {
					final AudioFormat targetFormat = new AudioFormat(format.getSampleRate(), 16, 2, true, false);
					try (AudioInputStream pcmStream = AudioSystem.getAudioInputStream(targetFormat, audioInputStream)) {
						playPcmStream(pcmStream, targetFormat);
					}
				}
			} catch (UnsupportedAudioFileException e) {
				throw new IOException(Text.translatable("gui.mtr.network_audio_unsupported_format").getString(), e);
			} catch (IllegalArgumentException e) {
				throw new IOException(Text.translatable("gui.mtr.network_audio_unsupported_format").getString(), e);
			}
		}

		private void playPcmStream(InputStream inputStream, AudioFormat format) throws Exception {
			SourceDataLine line = openLine(format);
			final InputStream playStream;
			if (line != null) {
				playStream = inputStream;
			} else {
				final AudioInputStream sourceStream = new AudioInputStream(inputStream, format, AudioSystem.NOT_SPECIFIED);
				AudioFormat targetFormat = new AudioFormat(format.getSampleRate(), 16, 2, true, false);
				AudioInputStream convertedStream;
				try {
					convertedStream = AudioSystem.getAudioInputStream(targetFormat, sourceStream);
				} catch (IllegalArgumentException e) {
					targetFormat = new AudioFormat(format.getSampleRate(), 16, 1, true, false);
					convertedStream = AudioSystem.getAudioInputStream(targetFormat, sourceStream);
				}
				line = openLine(targetFormat);
				if (line == null) {
					throw new IOException(Text.translatable("gui.mtr.network_audio_unsupported_format").getString());
				}
				playStream = convertedStream;
			}
			try {
				line.start();
				boolean notified = false;
				final byte[] buffer = new byte[IO_BUFFER_SIZE];
				int length;
				while (!cancelled && (length = playStream.read(buffer)) > 0) {
					line.write(buffer, 0, length);
					if (!notified) {
						notified = true;
						notifyStatus(Status.PLAYING, null);
						System.out.println("[MTR] Network audio: playing " + urlString);
					}
				}
				if (!cancelled) {
					line.drain();
				}
			} finally {
				line.close();
				if (playStream instanceof AudioInputStream) {
					try {
						((AudioInputStream) playStream).close();
					} catch (IOException ignored) {
					}
				}
			}
		}

		private Thread startDownloadThread(InputStream networkStream, StreamBuffer streamBuffer) {
			final Thread thread = new Thread(() -> {
				try {
					final byte[] buffer = new byte[IO_BUFFER_SIZE];
					int length;
					while (!cancelled && (length = networkStream.read(buffer)) > 0) {
						streamBuffer.push(buffer, length);
					}
					if (cancelled) {
						streamBuffer.pushError(new InterruptedIOException("Cancelled"));
					} else {
						streamBuffer.pushEof();
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} catch (IOException e) {
					if (!cancelled) {
						streamBuffer.pushError(e);
					}
				} finally {
					try {
						networkStream.close();
					} catch (IOException ignored) {
					}
				}
			}, "MTR-NetworkAudio-Download");
			thread.setDaemon(true);
			thread.start();
			return thread;
		}

		private byte[] downloadAll(InputStream inputStream) throws IOException {
			final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
			final byte[] buffer = new byte[IO_BUFFER_SIZE];
			int length;
			while (!cancelled && (length = inputStream.read(buffer)) > 0) {
				byteArrayOutputStream.write(buffer, 0, length);
			}
			if (cancelled) {
				throw new InterruptedIOException("Cancelled");
			}
			final byte[] data = byteArrayOutputStream.toByteArray();
			if (data.length <= MAX_CACHE_FILE_SIZE) {
				AUDIO_CACHE.put(urlString, data);
			}
			return data;
		}

		private void notifyStatus(Status status, Component message) {
			final StatusCallback statusCallback = callback;
			if (statusCallback != null) {
				try {
					statusCallback.onStatus(status, message);
				} catch (Exception ignored) {
				}
			}
		}

		private static void sleepInterruptibly(long millis) throws InterruptedException {
			final long endTime = System.currentTimeMillis() + millis;
			while (System.currentTimeMillis() < endTime) {
				Thread.sleep(Math.min(100, endTime - System.currentTimeMillis()));
			}
		}

		private static String getErrorMessage(Exception e) {
			return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
		}

		private static HttpURLConnection openConnectionWithRedirects(URL initialUrl) throws IOException {
			URL currentUrl = initialUrl;
			for (int i = 0; i <= MAX_REDIRECTS; i++) {
				final HttpURLConnection httpConnection = (HttpURLConnection) currentUrl.openConnection();
				httpConnection.setConnectTimeout(CONNECT_TIMEOUT_MS);
				httpConnection.setReadTimeout(READ_TIMEOUT_MS);
				httpConnection.setRequestMethod("GET");
				httpConnection.setInstanceFollowRedirects(false);
				httpConnection.setRequestProperty("User-Agent", "MTR-Mod-NetworkAudio");
				httpConnection.setRequestProperty("Accept", "audio/*");
				final int responseCode = httpConnection.getResponseCode();
				if (responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP || responseCode == HttpURLConnection.HTTP_SEE_OTHER || responseCode == 307 || responseCode == 308) {
					final String location = httpConnection.getHeaderField("Location");
					httpConnection.disconnect();
					if (location == null) {
						throw new IOException("HTTP " + responseCode);
					}
					try {
						currentUrl = URI.create(currentUrl.toURI().resolve(location).toString()).toURL();
					} catch (URISyntaxException e) {
						throw new IOException("Invalid redirect location: " + location, e);
					}
				} else if (responseCode != HttpURLConnection.HTTP_OK) {
					httpConnection.disconnect();
					throw new IOException("HTTP " + responseCode);
				} else {
					return httpConnection;
				}
			}
			throw new IOException("Too many redirects");
		}

		private static AudioFormat parseWavHeader(InputStream inputStream) throws IOException {
			final byte[] header = new byte[12];
			readFully(inputStream, header);
			if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F' || header[8] != 'W' || header[9] != 'A' || header[10] != 'V' || header[11] != 'E') {
				throw new IOException(Text.translatable("gui.mtr.network_audio_invalid").getString());
			}

			AudioFormat format = null;
			final byte[] chunkHeader = new byte[8];
			while (true) {
				readFully(inputStream, chunkHeader);
				final String chunkId = new String(chunkHeader, 0, 4, StandardCharsets.US_ASCII);
				final long chunkSize = readLittleEndianUInt32(chunkHeader, 4);
				if ("fmt ".equals(chunkId)) {
					final int headerLength = (int) Math.min(chunkSize, 64);
					final byte[] fmtData = new byte[headerLength];
					readFully(inputStream, fmtData);
					skipFully(inputStream, chunkSize - headerLength + (chunkSize % 2));
					final int audioFormat = readLittleEndianUInt16(fmtData, 0);
					final int channels = readLittleEndianUInt16(fmtData, 2);
					final float sampleRate = readLittleEndianUInt32(fmtData, 4);
					final int bitsPerSample = readLittleEndianUInt16(fmtData, 14);
					final AudioFormat.Encoding encoding = getWavEncoding(audioFormat, bitsPerSample, fmtData);
					if (encoding == null || channels <= 0 || sampleRate <= 0) {
						throw new IOException(Text.translatable("gui.mtr.network_audio_unsupported_format").getString());
					}
					format = new AudioFormat(encoding, sampleRate, bitsPerSample, channels, (channels * bitsPerSample + 7) / 8, sampleRate, false);
				} else if ("data".equals(chunkId)) {
					if (format == null) {
						throw new IOException(Text.translatable("gui.mtr.network_audio_invalid").getString());
					}
					return format;
				} else {
					skipFully(inputStream, chunkSize + (chunkSize % 2));
				}
			}
		}

		private static AudioFormat.Encoding getWavEncoding(int audioFormat, int bitsPerSample, byte[] fmtData) {
			final int subFormat;
			if (audioFormat == 0xFFFE && fmtData.length >= 26) {
				subFormat = readLittleEndianUInt16(fmtData, 24);
			} else {
				subFormat = audioFormat;
			}
			if (subFormat == 1) {
				return bitsPerSample == 8 ? AudioFormat.Encoding.PCM_UNSIGNED : AudioFormat.Encoding.PCM_SIGNED;
			}
			if (subFormat == 3) {
				return AudioFormat.Encoding.PCM_FLOAT;
			}
			return null;
		}

		private static boolean isPcmEncoding(AudioFormat.Encoding encoding) {
			return encoding.equals(AudioFormat.Encoding.PCM_SIGNED) || encoding.equals(AudioFormat.Encoding.PCM_UNSIGNED) || encoding.equals(AudioFormat.Encoding.PCM_FLOAT);
		}

		private static SourceDataLine openLine(AudioFormat format) {
			try {
				final DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
				if (!AudioSystem.isLineSupported(info)) {
					return null;
				}
				final SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
				line.open(format, IO_BUFFER_SIZE * 8);
				return line;
			} catch (LineUnavailableException | IllegalArgumentException e) {
				return null;
			}
		}

		private static int readSome(InputStream inputStream, byte[] buffer) throws IOException {
			int offset = 0;
			while (offset < buffer.length) {
				final int length = inputStream.read(buffer, offset, buffer.length - offset);
				if (length < 0) {
					break;
				}
				offset += length;
			}
			return offset;
		}

		private static void readFully(InputStream inputStream, byte[] buffer) throws IOException {
			int offset = 0;
			while (offset < buffer.length) {
				final int length = inputStream.read(buffer, offset, buffer.length - offset);
				if (length < 0) {
					throw new IOException(Text.translatable("gui.mtr.network_audio_invalid").getString());
				}
				offset += length;
			}
		}

		private static void skipFully(InputStream inputStream, long count) throws IOException {
			long remaining = count;
			while (remaining > 0) {
				final long skipped = inputStream.skip(remaining);
				if (skipped > 0) {
					remaining -= skipped;
				} else {
					if (inputStream.read() < 0) {
						throw new IOException(Text.translatable("gui.mtr.network_audio_invalid").getString());
					}
					remaining--;
				}
			}
		}

		private static int readLittleEndianUInt16(byte[] data, int offset) {
			return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
		}

		private static long readLittleEndianUInt32(byte[] data, int offset) {
			return (data[offset] & 0xFFL) | ((data[offset + 1] & 0xFFL) << 8) | ((data[offset + 2] & 0xFFL) << 16) | ((data[offset + 3] & 0xFFL) << 24);
		}

		private class StreamBuffer extends InputStream {

			private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
			private final long maxBufferedBytes;
			private final AtomicLong pendingBytes = new AtomicLong();
			private byte[] currentChunk;
			private int currentChunkPosition;

			private StreamBuffer(long maxBufferedBytes) {
				this.maxBufferedBytes = maxBufferedBytes;
			}

			private void push(byte[] data, int length) throws InterruptedException {
				while (pendingBytes.get() > maxBufferedBytes && !cancelled && playThread != null && playThread.isAlive() && !Thread.currentThread().isInterrupted()) {
					Thread.sleep(10);
				}
				if (cancelled || Thread.currentThread().isInterrupted() || playThread == null || !playThread.isAlive()) {
					throw new InterruptedException();
				}
				final byte[] chunk = new byte[length];
				System.arraycopy(data, 0, chunk, 0, length);
				queue.put(chunk);
				pendingBytes.addAndGet(length);
			}

			private void pushEof() {
				queue.offer(EOF_MARKER);
			}

			private void pushError(Throwable error) {
				queue.offer(error);
			}

			@Override
			public int read() throws IOException {
				final byte[] single = new byte[1];
				final int length = read(single, 0, 1);
				return length < 0 ? -1 : single[0] & 0xFF;
			}

			@Override
			public int read(byte[] buffer, int offset, int length) throws IOException {
				if (length == 0) {
					return 0;
				}
				while (currentChunk == null || currentChunkPosition >= currentChunk.length) {
					final Object item;
					try {
						item = queue.take();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new IOException(Text.translatable("gui.mtr.network_audio_interrupted").getString());
					}
					if (item == EOF_MARKER) {
						return -1;
					}
					if (item instanceof Throwable) {
						if (item instanceof IOException) {
							throw (IOException) item;
						}
						throw new IOException(String.valueOf(((Throwable) item).getMessage()), (Throwable) item);
					}
					currentChunk = (byte[]) item;
					currentChunkPosition = 0;
					pendingBytes.addAndGet(-currentChunk.length);
				}
				final int copyLength = Math.min(length, currentChunk.length - currentChunkPosition);
				System.arraycopy(currentChunk, currentChunkPosition, buffer, offset, copyLength);
				currentChunkPosition += copyLength;
				return copyLength;
			}
		}
	}
}
