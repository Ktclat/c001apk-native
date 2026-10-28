"""Pure synthetic raw-log controls; not Android runtime output."""
import unittest
from runner_terminal import qualify

PASS=b'INSTRUMENTATION_STATUS: class=synthetic.Class\nINSTRUMENTATION_STATUS: test=case\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: class=synthetic.Class\nINSTRUMENTATION_STATUS: test=case\nINSTRUMENTATION_STATUS_CODE: 0\n'
END=b'INSTRUMENTATION_RESULT: stream=\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n'
class WholeRunnerTerminal(unittest.TestCase):
    def test_normal(self): self.assertTrue(qualify(PASS+END)['qualified'])
    def test_pass_then_crash_is_not_success(self):
        self.assertFalse(qualify(PASS+b'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n')['qualified'])
        self.assertFalse(qualify(PASS+b'INSTRUMENTATION_RESULT: shortMsg=Crash\n'+END)['qualified'])
    def test_missing_terminal(self): self.assertFalse(qualify(PASS)['qualified'])
    def test_duplicate_terminal(self): self.assertFalse(qualify(PASS+END+END)['qualified'])
    def test_run_failed(self): self.assertFalse(qualify(PASS+b'INSTRUMENTATION_FAILED: runner\n'+END)['qualified'])
    def test_status_after_final_is_not_success(self): self.assertFalse(qualify(PASS+END+PASS)['qualified'])
