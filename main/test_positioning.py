from __future__ import annotations

import unittest
from pathlib import Path

from positioning import POSITION, PUBLISH_BRANCH, ScopeViolation


class AgentPositioningTests(unittest.TestCase):
    def test_scope_is_interface_test_directory(self) -> None:
        self.assertEqual(POSITION.scope_root.name, "接口测试")
        self.assertEqual(POSITION.scope_root.parent.name, "draft")

    def test_main_directory_is_declared(self) -> None:
        self.assertEqual(POSITION.main_directory, POSITION.scope_root / "main")
        self.assertTrue(POSITION.main_directory.is_dir())

    def test_repository_target_is_orderly(self) -> None:
        self.assertEqual(POSITION.repository, "xinge233/Orderly")
        self.assertEqual(POSITION.repository_url, "https://github.com/xinge233/Orderly")

    def test_agents_file_is_readable(self) -> None:
        self.assertEqual(POSITION.require_agents_file(), POSITION.scope_root / "AGENTS.md")

    def test_relative_path_resolves_inside_scope(self) -> None:
        resolved = POSITION.require_local_path("fixtures/example.json")
        self.assertEqual(resolved, POSITION.scope_root / "fixtures" / "example.json")

    def test_parent_traversal_is_rejected(self) -> None:
        with self.assertRaises(ScopeViolation):
            POSITION.require_local_path("../app")

    def test_absolute_path_outside_scope_is_rejected(self) -> None:
        outside = Path(POSITION.scope_root.anchor) / "outside-test"
        with self.assertRaises(ScopeViolation):
            POSITION.require_local_operation("write", outside)

    def test_github_write_is_rejected(self) -> None:
        with self.assertRaises(ScopeViolation):
            POSITION.require_remote_operation("write", branch=PUBLISH_BRANCH)

    def test_explicitly_authorized_github_write_is_allowed(self) -> None:
        POSITION.require_remote_operation(
            "write",
            branch=PUBLISH_BRANCH,
            explicitly_authorized=True,
        )
        self.assertEqual(PUBLISH_BRANCH, "interface-testing-active")

    def test_github_read_is_allowed(self) -> None:
        POSITION.require_remote_operation("read", branch=PUBLISH_BRANCH)

    def test_main_branch_is_always_rejected(self) -> None:
        with self.assertRaises(ScopeViolation):
            POSITION.require_remote_operation("read", branch="main")

    def test_other_branches_are_rejected(self) -> None:
        with self.assertRaises(ScopeViolation):
            POSITION.require_remote_operation(
                "write",
                branch="interface-testing",
                explicitly_authorized=True,
            )


if __name__ == "__main__":
    unittest.main()
