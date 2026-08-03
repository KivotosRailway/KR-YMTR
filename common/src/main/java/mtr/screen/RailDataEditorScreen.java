package mtr.screen;

import mtr.client.AnteRailCompat;
import mtr.client.IDrawing;
import mtr.item.ItemRailDataEditor.RailInfo;
import mtr.mappings.ScreenMapper;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.PacketUpdateRailData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RailDataEditorScreen extends ScreenMapper {

	private static final int PANEL_WIDTH = 380;
	private static final int PANEL_HEIGHT = 300;
	private static final int ROW_HEIGHT = 24;
	private static final int ROW_SPACING = 26;
	private static final String TYPE_DELIMITER = "__type__";
	private static final String[] TYPES = {"string", "int", "float", "bool"};

	private final List<RailInfo> railInfos;
	private int railIndex = 0;
	private final List<Entry> entries = new ArrayList<>();

	private double scrollTarget = 0;
	private double scroll = 0;
	private double maxScroll = 0;

	private Button buttonPrevRail;
	private Button buttonNextRail;
	private Button buttonAdd;
	private Button buttonSave;
	private Button buttonClose;
	private final List<WidgetBetterTextField> nameFields = new ArrayList<>();
	private final List<Button> typeButtons = new ArrayList<>();
	private final List<WidgetBetterTextField> valueFields = new ArrayList<>();
	private final List<Button> deleteButtons = new ArrayList<>();

	private int panelX;
	private int panelY;
	private int listX;
	private int listY;
	private int listWidth;
	private int listHeight;

	public static class Entry {
		public String name = "";
		public String type = "string";
		public String value = "";
	}

	public RailDataEditorScreen(List<RailInfo> railInfos) {
		super(Text.translatable("gui.mtr.rail_data_editor.title"));
		this.railInfos = railInfos;
	}

	@Override
	protected void init() {
		super.init();
		panelX = (width - PANEL_WIDTH) / 2;
		panelY = (height - PANEL_HEIGHT) / 2;
		listX = panelX + 8;
		listY = panelY + 80;
		listWidth = PANEL_WIDTH - 16;
		listHeight = PANEL_HEIGHT - 80 - 12;

		buttonPrevRail = UtilitiesClient.newButton(Text.literal("◀"), button -> switchRail(-1));
		buttonNextRail = UtilitiesClient.newButton(Text.literal("▶"), button -> switchRail(1));
		buttonAdd = UtilitiesClient.newButton(Text.translatable("gui.mtr.rail_data_editor.add"), button -> addEntry());
		buttonSave = UtilitiesClient.newButton(Text.translatable("gui.mtr.rail_data_editor.save"), button -> save());
		buttonClose = UtilitiesClient.newButton(Text.translatable("gui.mtr.rail_data_editor.close"), button -> onClose());

		IDrawing.setPositionAndWidth(buttonPrevRail, panelX + 12, panelY + 52, 24);
		IDrawing.setPositionAndWidth(buttonNextRail, panelX + 42, panelY + 52, 24);
		IDrawing.setPositionAndWidth(buttonAdd, panelX + 76, panelY + 52, 96);
		IDrawing.setPositionAndWidth(buttonSave, panelX + PANEL_WIDTH - 12 - 70 - 8 - 70, panelY + 52, 70);
		IDrawing.setPositionAndWidth(buttonClose, panelX + PANEL_WIDTH - 12 - 70, panelY + 52, 70);

		addDrawableChild(buttonPrevRail);
		addDrawableChild(buttonNextRail);
		addDrawableChild(buttonAdd);
		addDrawableChild(buttonSave);
		addDrawableChild(buttonClose);
		buttonPrevRail.visible = railInfos.size() > 1;
		buttonNextRail.visible = railInfos.size() > 1;

		loadFromRail();
	}

	@Override
	public void tick() {
		super.tick();
		final double newScroll = Mth.lerp(0.2, scroll, scrollTarget);
		scroll = Math.abs(newScroll - scrollTarget) < 0.5 ? scrollTarget : newScroll;
		updateRowPositions();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (mouseX >= listX && mouseX <= listX + listWidth && mouseY >= listY && mouseY <= listY + listHeight) {
			scrollTarget = Mth.clamp(scrollTarget - amount * 10, 0, maxScroll);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		guiGraphics.fill(0, 0, width, height, 0x88000000);
		guiGraphics.fill(panelX - 1, panelY - 1, panelX + PANEL_WIDTH + 1, panelY + PANEL_HEIGHT + 1, 0xFF6E6E6E);
		guiGraphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, 0xEE2B2B2B);
		guiGraphics.drawCenteredString(font, Text.translatable("gui.mtr.rail_data_editor.title").getString(), panelX + PANEL_WIDTH / 2, panelY + 12, 0xFFFFFFFF);
		final String railText = railInfos.size() > 1 ? (railIndex + 1) + "/" + railInfos.size() + "  " + railString(currentInfo()) : railString(currentInfo());
		guiGraphics.drawCenteredString(font, railText, panelX + PANEL_WIDTH / 2, panelY + 32, 0xFFBBBBBB);
		guiGraphics.fill(listX, listY, listX + listWidth, listY + listHeight, 0xAA1C1C1C);
		final int baseY = listY + 2;
		for (int i = 0; i < entries.size(); i++) {
			final int y = (int) Math.round(baseY + i * ROW_SPACING - scroll);
			if (y + ROW_HEIGHT > listY && y < listY + listHeight) {
				guiGraphics.fill(listX + 1, y, listX + listWidth - 1, y + ROW_HEIGHT, i % 2 == 0 ? 0x33FFFFFF : 0x11FFFFFF);
			}
		}
		super.render(guiGraphics, mouseX, mouseY, delta);
		if (maxScroll > 0) {
			final int barHeight = Math.max(12, (int) (listHeight * listHeight / (entries.size() * ROW_SPACING + 4)));
			final int barY = listY + (int) ((listHeight - barHeight) * scroll / maxScroll);
			guiGraphics.fill(listX + listWidth - 3, barY, listX + listWidth, barY + barHeight, 0xFF888888);
		}
		guiGraphics.drawString(font, Text.translatable("gui.mtr.rail_data_editor.hint").getString(), panelX + 12, panelY + PANEL_HEIGHT - 12, 0xFF777777);
	}

	@Override
	public void onClose() {
		super.onClose();
	}

	private RailInfo currentInfo() {
		return railInfos.get(railIndex);
	}

	private static String railString(RailInfo info) {
		return info.posStart.getX() + ", " + info.posStart.getY() + ", " + info.posStart.getZ() + "  →  " + info.posEnd.getX() + ", " + info.posEnd.getY() + ", " + info.posEnd.getZ();
	}

	private void switchRail(int delta) {
		if (railInfos.size() <= 1) {
			return;
		}
		saveInternal(false);
		railIndex = (railIndex + delta + railInfos.size()) % railInfos.size();
		scroll = 0;
		scrollTarget = 0;
		loadFromRail();
	}

	private void loadFromRail() {
		entries.clear();
		final RailInfo info = currentInfo();
		final Map<String, String> customConfigs = new HashMap<>(info.rail.getRailData());
		customConfigs.putAll(AnteRailCompat.getRailCustomConfigs(info.rail));
		for (Map.Entry<String, String> entry : customConfigs.entrySet()) {
			if (entry.getKey().startsWith(TYPE_DELIMITER)) {
				continue;
			}
			final Entry data = new Entry();
			data.name = entry.getKey();
			data.type = normalizeType(customConfigs.get(TYPE_DELIMITER + entry.getKey()));
			data.value = entry.getValue();
			entries.add(data);
		}
		rebuildRows();
	}

	private void addEntry() {
		final Entry entry = new Entry();
		entry.name = "data" + (entries.size() + 1);
		entries.add(entry);
		rebuildRows();
		scrollTarget = maxScroll;
	}

	private void removeEntry(Entry entry) {
		entries.remove(entry);
		rebuildRows();
	}

	private void save() {
		saveInternal(true);
	}

	private void saveInternal(boolean close) {
		final RailInfo info = currentInfo();
		final Map<String, String> customConfigs = new HashMap<>();
		for (Entry entry : entries) {
			if (entry.name.isEmpty()) {
				continue;
			}
			customConfigs.put(entry.name, entry.value);
			customConfigs.put(TYPE_DELIMITER + entry.name, entry.type);
		}
		info.rail.setRailData(customConfigs);
		AnteRailCompat.setRailCustomConfigs(info.rail, customConfigs);
		PacketUpdateRailData.sendUpdateC2S(customConfigs, info.posStart, info.posEnd);
		if (close) {
			onClose();
		}
	}

	private void rebuildRows() {
		for (WidgetBetterTextField textField : nameFields) {
			removeWidget(textField);
		}
		for (Button button : typeButtons) {
			removeWidget(button);
		}
		for (WidgetBetterTextField textField : valueFields) {
			removeWidget(textField);
		}
		for (Button button : deleteButtons) {
			removeWidget(button);
		}
		nameFields.clear();
		typeButtons.clear();
		valueFields.clear();
		deleteButtons.clear();

		for (Entry entry : entries) {
			final WidgetBetterTextField nameField = new WidgetBetterTextField(Text.translatable("gui.mtr.rail_data_editor.name").getString(), 64);
			nameField.setWidth(96);
			nameField.setValue(entry.name);
			nameField.setResponder(text -> entry.name = text);

			final Button typeButton = UtilitiesClient.newButton(Text.literal(entry.type), button -> {
				entry.type = nextType(entry.type);
				button.setMessage(Text.literal(entry.type));
			});

			final WidgetBetterTextField valueField = new WidgetBetterTextField(Text.translatable("gui.mtr.rail_data_editor.value").getString());
			valueField.setWidth(listWidth - 184 - 2);
			valueField.setValue(entry.value);
			valueField.setResponder(text -> entry.value = text);

			final Button deleteButton = UtilitiesClient.newButton(Text.literal("✕"), button -> removeEntry(entry));

			addDrawableChild(nameField);
			addDrawableChild(typeButton);
			addDrawableChild(valueField);
			addDrawableChild(deleteButton);
			nameFields.add(nameField);
			typeButtons.add(typeButton);
			valueFields.add(valueField);
			deleteButtons.add(deleteButton);
		}

		maxScroll = Math.max(0, entries.size() * ROW_SPACING - listHeight + 4);
		if (scrollTarget > maxScroll) {
			scrollTarget = maxScroll;
		}
		if (scroll > maxScroll) {
			scroll = maxScroll;
		}
		updateRowPositions();
	}

	private void updateRowPositions() {
		final int baseY = listY + 2;
		for (int i = 0; i < entries.size(); i++) {
			final int y = (int) Math.round(baseY + i * ROW_SPACING - scroll);
			final boolean visible = y + ROW_HEIGHT > listY && y < listY + listHeight;

			final WidgetBetterTextField nameField = nameFields.get(i);
			nameField.setVisible(visible);
			nameField.setX(listX + 24);
			nameField.setY(y);
			nameField.setWidth(96);

			final Button typeButton = typeButtons.get(i);
			typeButton.visible = visible;
			typeButton.setX(listX + 124);
			typeButton.setY(y);
			typeButton.setWidth(56);

			final WidgetBetterTextField valueField = valueFields.get(i);
			valueField.setVisible(visible);
			valueField.setX(listX + 184);
			valueField.setY(y);
			valueField.setWidth(listWidth - 184 - 2);

			final Button deleteButton = deleteButtons.get(i);
			deleteButton.visible = visible;
			deleteButton.setX(listX + 2);
			deleteButton.setY(y);
			deleteButton.setWidth(18);
		}
	}

	private static String nextType(String type) {
		for (int i = 0; i < TYPES.length; i++) {
			if (TYPES[i].equals(type)) {
				return TYPES[(i + 1) % TYPES.length];
			}
		}
		return TYPES[0];
	}

	private static String normalizeType(String type) {
		for (String candidate : TYPES) {
			if (candidate.equals(type)) {
				return candidate;
			}
		}
		return TYPES[0];
	}
}
