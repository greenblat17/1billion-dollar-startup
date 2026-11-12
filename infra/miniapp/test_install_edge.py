import importlib.util
from pathlib import Path
import unittest

HERE = Path(__file__).parent
spec = importlib.util.spec_from_file_location('edge', HERE / 'install-edge.py')
edge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(edge)


class EdgeReplacementTest(unittest.TestCase):
    def test_preserves_other_sites_and_is_idempotent(self):
        before = 'voice-lab.example {\n respond "lab"\n}\n# Mini App\n'
        old = edge.HOST + ' {\n @miniapp path /miniapp /miniapp/*\n handle @miniapp {\n reverse_proxy ai-service:8090\n }\n}\n'
        after = '\nother.example {\n respond "other"\n}\n'
        replacement = (HERE / 'Caddyfile.dev.example').read_text()
        result = edge.replace_site(before + old + after, replacement)
        self.assertTrue(result.startswith(before))
        self.assertTrue(result.endswith(after))
        self.assertIn('reverse_proxy mini-app:8080', result)
        self.assertIn('/miniapp/voice', result)
        self.assertEqual(result, edge.replace_site(result, replacement))

    def test_unknown_site_or_upstream_is_rejected(self):
        for source in ['other.example { }', edge.HOST + ' { reverse_proxy unexpected:9999 }']:
            with self.assertRaises(ValueError):
                edge.replace_site(source, (HERE / 'Caddyfile.dev.example').read_text())


if __name__ == '__main__':
    unittest.main()
