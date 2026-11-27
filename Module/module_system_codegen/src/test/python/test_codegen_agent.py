from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest


SCRIPT = Path(__file__).resolve().parents[3] / "ai" / "codegen_agent.py"


def load_agent():
    spec = importlib.util.spec_from_file_location("codegen_agent", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def test_normalizes_steps_and_rejects_duplicate_paths():
    agent = load_agent()
    proposal = agent._normalize_proposal(
        {
            "moduleId": "module_demo",
            "summary": "candidate",
            "steps": [
                {
                    "title": "contract",
                    "path": "src/main/resources/orderly-modules/module_demo.json",
                    "content": "{}",
                }
            ],
        },
        mode="module",
        target_module="",
    )

    assert proposal["moduleId"] == "module_demo"
    assert (
        proposal["steps"][0]["path"]
        == "src/main/resources/orderly-modules/module_demo.json"
    )

    with pytest.raises(ValueError, match="重复"):
        agent._normalize_proposal(
            {
                "summary": "candidate",
                "steps": [
                    {"path": "a.txt", "content": "a"},
                    {"path": "a.txt", "content": "b"},
                ],
            },
            mode="module",
            target_module="module_demo",
        )


@pytest.mark.parametrize(
    "path",
    ["../escape.css", "/absolute.css", "C:/escape.css", "a/../b.css", ""],
)
def test_rejects_unsafe_relative_paths(path):
    agent = load_agent()

    with pytest.raises(ValueError):
        agent._safe_relative_path(path)


def test_prompts_use_packaged_maven_resource_paths():
    agent = load_agent()
    module_prompt = agent._system_prompt(
        mode="module",
        target_module="",
        motion_target="",
        theme_id="paper",
    )
    motion_prompt = agent._system_prompt(
        mode="motion",
        target_module="module_demo",
        motion_target="module",
        theme_id="paper",
    )

    assert "src/main/resources/orderly-ui/module.json" in module_prompt
    assert "不要使用根目录级的 orderly-modules" in module_prompt
    assert "export default async function mount" in module_prompt
    assert '"version":1' in module_prompt
    assert "不要使用 schemaVersion" in module_prompt
    assert "JSON Schema" in module_prompt
    assert "outputSchema" in module_prompt
    assert "src/main/resources/orderly-ui/module.js" in motion_prompt
    assert "src/main/resources/orderly-ui/module.json" in motion_prompt
    assert "motionAnnotations" in motion_prompt
    assert "sourceHint" in motion_prompt


def test_normalizes_motion_nodes_and_annotations_with_bounds():
    agent = load_agent()
    nodes = agent._motion_nodes([
        {
            "node": "motion-node-1",
            "tag": "article",
            "selector": '[data-orderly-motion-node="motion-node-1"]',
            "classes": "study-card",
            "text": "复习第三章",
            "sourceHint": "module.js:182",
            "overlap": 0.82,
        },
        {"node": "", "tag": "section"},
    ])
    annotations = agent._motion_annotations([
        {
            "annotationId": "annotation-1",
            "kind": "rect",
            "points": [[-1, 0.4], [1.2, 0.8]],
            "text": "任务卡片",
            "nodes": nodes,
        }
    ])

    assert nodes[0]["node"] == "motion-node-1"
    assert nodes[0]["overlap"] == 0.82
    assert annotations[0]["annotationId"] == "annotation-1"
    assert annotations[0]["points"] == [[0.0, 0.4], [1.0, 0.8]]
    assert annotations[0]["nodes"][0]["sourceHint"] == "module.js:182"


def test_rejects_invalid_motion_annotation_without_points():
    agent = load_agent()
    assert agent._motion_annotations([
        {"annotationId": "bad", "kind": "rect", "points": []}
    ]) == []
