import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location(
    "ui_guardrails", Path(__file__).resolve().parents[1] / "ui-guardrails.py"
)
GUARDRAILS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GUARDRAILS)


class TextControlHeightTests(unittest.TestCase):
    def hits(self, source):
        return GUARDRAILS.fixed_text_control_height(source, "Example.kt")

    def test_fixed_button_and_field_heights_are_rejected(self):
        for control in ("Button", "TextButton", "ToggleButton", "OutlinedTextField"):
            with self.subTest(control=control):
                self.assertEqual(1, len(self.hits(
                    f"{control}(onClick = {{}}, modifier = Modifier.height(48.dp)) {{}}"
                )))

    def test_conditional_and_required_heights_are_rejected(self):
        self.assertEqual(1, len(self.hits(
            "Button(modifier = Modifier.height(if (compact) 48.dp else 56.dp)) {}"
        )))
        self.assertEqual(1, len(self.hits(
            "Button(modifier = Modifier.requiredHeight(buttonHeight)) {}"
        )))

    def test_qualified_material3_control_heights_are_rejected(self):
        for prefix in ("material3.", "androidx.compose.material3."):
            for control in (
                "Button", "FilledTonalButton", "OutlinedButton", "ElevatedButton",
                "TextButton", "ToggleButton", "TextField", "OutlinedTextField",
            ):
                with self.subTest(prefix=prefix, control=control):
                    self.assertEqual(1, len(self.hits(
                        f"{prefix}{control}(modifier = Modifier.height(48.dp)) {{}}"
                    )))

    def test_other_qualified_receivers_are_not_material3_controls(self):
        for prefix in ("custom.", "androidx.wear.compose.material3x.", "othermaterial3x."):
            with self.subTest(prefix=prefix):
                self.assertEqual([], self.hits(
                    f"{prefix}Button(modifier = Modifier.height(48.dp)) {{}}"
                ))

    def test_minimum_and_intrinsic_heights_can_grow(self):
        self.assertEqual([], self.hits(
            "Button(modifier = Modifier.heightIn(min = 48.dp).height(IntrinsicSize.Min)) {}"
        ))

    def test_nested_callback_decorations_do_not_size_the_button(self):
        self.assertEqual([], self.hits('''Button(
            onClick = { show { Box(Modifier.height(48.dp)) } },
            modifier = Modifier.heightIn(min = 48.dp),
            colors = colors("ignore: ) .height(56.dp)"),
        ) {}'''))

    def test_nested_label_icons_do_not_size_the_field(self):
        self.assertEqual([], self.hits('''OutlinedTextField(
            label = { Box(Modifier.height(56.dp)) { Text(label) } },
            modifier = Modifier.fillMaxWidth(),
        )'''))

    def test_icon_only_buttons_keep_their_geometry(self):
        self.assertEqual([], self.hits(
            "IconButton(modifier = Modifier.height(48.dp)) {}"
        ))


if __name__ == "__main__":
    unittest.main()
