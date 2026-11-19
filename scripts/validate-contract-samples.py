#!/usr/bin/env python3
"""Validate one JSON Schema instance using jsonschema's Draft validator."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Iterator


EXIT_VALID = 0
EXIT_INVALID = 1
EXIT_USAGE_OR_SCHEMA = 2


def write_diagnostic(path: str, validator: str) -> None:
    print(f"path={path} validator={validator}", file=sys.stderr)


def find_external_refs(value: Any, location: str = "#") -> Iterator[str]:
    if isinstance(value, dict):
        for key, child in value.items():
            child_location = f"{location}/{key}"
            if key == "$ref" and isinstance(child, str):
                if not child.startswith("#"):
                    yield child_location
                continue
            yield from find_external_refs(child, child_location)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from find_external_refs(child, f"{location}/{index}")


def load_json(path_text: str, validator_name: str) -> tuple[Any, Path]:
    path = Path(path_text)
    try:
        with path.open("r", encoding="utf-8-sig") as source:
            return json.load(source), path
    except (OSError, UnicodeError, json.JSONDecodeError):
        write_diagnostic(path_text, validator_name)
        raise SystemExit(EXIT_USAGE_OR_SCHEMA)


def json_pointer(path_parts: Any) -> str:
    escaped = [
        str(part).replace("~", "~0").replace("/", "~1")
        for part in path_parts
    ]
    return "/" + "/".join(escaped) if escaped else "/"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("schema")
    parser.add_argument("sample")
    args = parser.parse_args()

    try:
        from jsonschema import FormatChecker
        from jsonschema.exceptions import SchemaError
        from jsonschema.validators import validator_for
    except ImportError:
        write_diagnostic(args.schema, "jsonschema-dependency")
        return EXIT_USAGE_OR_SCHEMA

    schema, _ = load_json(args.schema, "json")
    sample, _ = load_json(args.sample, "json")

    external_refs = list(find_external_refs(schema))
    if external_refs:
        write_diagnostic(args.schema, "external-ref")
        return EXIT_USAGE_OR_SCHEMA

    try:
        validator_class = validator_for(schema)
        validator_class.check_schema(schema)
        validator = validator_class(
            schema,
            format_checker=FormatChecker(),
        )
        errors = sorted(
            validator.iter_errors(sample),
            key=lambda error: (
                tuple(str(part) for part in error.absolute_path),
                error.validator or "",
            ),
        )
    except SchemaError:
        write_diagnostic(args.schema, "schema")
        return EXIT_USAGE_OR_SCHEMA
    except Exception:
        write_diagnostic(args.sample, "validation")
        return EXIT_USAGE_OR_SCHEMA

    if errors:
        for error in errors:
            location = f"{args.sample}#{json_pointer(error.absolute_path)}"
            write_diagnostic(location, error.validator or "unknown")
        return EXIT_INVALID

    return EXIT_VALID


if __name__ == "__main__":
    raise SystemExit(main())
