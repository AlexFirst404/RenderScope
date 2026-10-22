"""Regenerate API-family UI and report copies from the shared product implementation."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "mc21"
source = (ROOT / "src/main/java/dev/renderscope/client/ui/RenderScopeScreen.java").read_text(encoding="utf-8")

def replace_once(text, old, new):
    if text.count(old) != 1:
        raise RuntimeError("Shared UI changed; review adapter marker: " + old)
    return text.replace(old, new)

base = replace_once(source, "renderBackground(g);", "renderBackground(g, mouseX, mouseY, partialTick);")
base = replace_once(base, "mouseScrolled(double mouseX, double mouseY, double delta)", "mouseScrolled(double mouseX, double mouseY, double horizontal, double delta)")
base = replace_once(base, "super.mouseScrolled(mouseX, mouseY, delta)", "super.mouseScrolled(mouseX, mouseY, horizontal, delta)")
base = replace_once(base, "        if (search != null) search.tick();\n", "")
# Native Screen owns background scheduling: render() through 1.21.5 and
# renderWithTooltip() from 1.21.6. Put our background/content in that callback,
# then let the native renderer draw every registered widget and tooltip once.
base = replace_once(base,
    "public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {\n        renderBackground(g, mouseX, mouseY, partialTick);",
    "public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {\n        super.renderBackground(g, mouseX, mouseY, partialTick);")
base = replace_once(base,
    "        drawScrollbar(g);\n        super.render(g, mouseX, mouseY, partialTick);",
    "        drawScrollbar(g);\n    }\n\n    @Override\n    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {\n        super.render(g, mouseX, mouseY, partialTick);")
identifier = 'new ResourceLocation("renderscope", "textures/gui/logo.png")'
modern = replace_once(base, identifier, 'ResourceLocation.fromNamespaceAndPath("renderscope", "textures/gui/logo.png")')
state = replace_once(modern, "import net.minecraft.resources.ResourceLocation;", "import net.minecraft.resources.ResourceLocation;\nimport net.minecraft.client.renderer.RenderType;")
state = replace_once(state, "g.blit(LOGO,", "g.blit(RenderType::guiTextured, LOGO,")
gui = replace_once(modern, "import net.minecraft.resources.ResourceLocation;", "import net.minecraft.resources.ResourceLocation;\nimport net.minecraft.client.renderer.RenderPipelines;\nimport net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;\nimport net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;")
gui = replace_once(gui, "g.blit(LOGO,", "g.blit(RenderPipelines.GUI_TEXTURED, LOGO,")
gui = replace_once(gui, "g.renderTooltip(font, lines, mouseX, mouseY);", "g.renderTooltip(font, lines.stream().map(ClientTooltipComponent::create).toList(), mouseX, mouseY, DefaultTooltipPositioner.INSTANCE, null);")
for family, text in (("main", modern), ("pre121", base), ("state", state), ("gui", gui)):
    target = MODULE / ("src/" + family + "/java/dev/renderscope/client/ui/RenderScopeScreen.java")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8", newline="\n")
    print(target.relative_to(ROOT))

# Keep the allowlist serializer and its privacy fixes shared. These ports do not
# enable the inspected 1.20.1-only optional hooks, so their coverage text differs.
report = (ROOT / "src/main/java/dev/renderscope/client/report/ReportExporter.java").read_text(encoding="utf-8")
report_lines = []
for line in report.splitlines():
    if 'root.addProperty("coverageMeaning",' in line:
        line = '        root.addProperty("coverageMeaning","Standard individual entity preparation and entity/block entity renderer calls. Terrain, particles, GUI and third-party instanced/deferred paths are not attributed by this adapter; totals are not the complete cost of a mod.");'
    elif 'root.addProperty("gpuMeaning",' in line:
        line = '        root.addProperty("gpuMeaning","CPU timers do not measure GPU execution. This adapter does not collect per-object GPU timing.");'
    report_lines.append(line)
report = "\n".join(report_lines) + "\n"
for family, text in (("main", report), ("gui", replace_once(report,
        "SharedConstants.getCurrentVersion().getName()", "SharedConstants.getCurrentVersion().name()"))):
    target = MODULE / ("src/" + family + "/java/dev/renderscope/client/report/ReportExporter.java")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8", newline="\n")
    print(target.relative_to(ROOT))
