"""Derive the small 1.20.2-1.20.4 screen API adaptation from the shared UI."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = (root / 'src/main/java/dev/renderscope/client/ui/RenderScopeScreen.java').read_text(encoding='utf-8')
source = source.replace('renderBackground(g);', 'renderBackground(g, mouseX, mouseY, partialTick);')
source = source.replace('        if (search != null) search.tick();\n', '')
source = source.replace('mouseScrolled(double mouseX, double mouseY, double delta)',
                        'mouseScrolled(double mouseX, double mouseY, double horizontal, double delta)')
source = source.replace('super.mouseScrolled(mouseX, mouseY, delta)',
                        'super.mouseScrolled(mouseX, mouseY, horizontal, delta)')
target = root / 'mc20/src/main/java/dev/renderscope/client/ui/RenderScopeScreen.java'
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(source, encoding='utf-8', newline='\n')
