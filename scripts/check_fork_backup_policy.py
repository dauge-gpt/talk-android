#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Check the fork's backup exclusions in source or a merged Android manifest."""

import argparse
from pathlib import Path
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
DOMAINS = {
    "root", "file", "database", "sharedpref", "external",
    "device_root", "device_file", "device_database", "device_sharedpref",
}


def check_exclusions(element: ET.Element, label: str) -> None:
    """Require whole-domain exclusions; an include rule could weaken the policy."""
    excluded = {
        node.get("domain") for node in element.findall("exclude")
        if node.get("path") == "."
    }
    if excluded != DOMAINS or element.findall("include"):
        raise ValueError(f"{label}: expected exclusions for all private storage domains")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--manifest", type=Path,
        default=ROOT / "app/src/main/AndroidManifest.xml",
        help="Source or merged manifest to inspect",
    )
    args = parser.parse_args()
    application = ET.parse(args.manifest).getroot().find("application")
    if application is None:
        raise ValueError("Manifest has no application")
    expected = {
        "allowBackup": "false",
        "fullBackupContent": "@xml/backup_config",
        "dataExtractionRules": "@xml/data_extraction_rules",
    }
    for key, value in expected.items():
        if application.get(ANDROID + key) != value:
            raise ValueError(f"Manifest: {key} must be {value}")
    for manifest in (ROOT / "app/src").glob("*/AndroidManifest.xml"):
        overlay = ET.parse(manifest).getroot().find("application")
        if overlay is not None:
            for key, value in expected.items():
                override = overlay.get(ANDROID + key)
                if override is not None and override != value:
                    raise ValueError(f"{manifest}: {key} overrides the backup policy")
    if application.get(ANDROID + "backupAgent"):
        raise ValueError("Custom BackupAgent requires a separate policy review")

    resources = ROOT / "app/src/main/res/xml"
    legacy = ET.parse(resources / "backup_config.xml").getroot()
    check_exclusions(legacy, "Android 8–11 backup")
    modern = ET.parse(resources / "data_extraction_rules.xml").getroot()
    for mode in ("cloud-backup", "device-transfer"):
        element = modern.find(mode)
        if element is None:
            raise ValueError(f"Missing {mode} policy")
        check_exclusions(element, mode)
    print("PASS: backup manifest flags and cloud/device-transfer exclusions")


if __name__ == "__main__":
    main()
