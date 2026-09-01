#!/usr/bin/env python3
# Copyright (c) 2026 AltaStata Inc. All rights reserved.
#
# This software is dual-licensed. It is licensed under the Business Source License 1.1
# (BSL) for open use and evaluation, with an eventual transition to the Apache 2.0
# license on the Change Date.
#
# PATENT NOTICE: Protected by US Patent No. 10,693,660.
#
# For the full license text, see the LICENSE.md file in the root of the repository,
# or https://github.com/AltaStata/sovereign-data-fabric/blob/main/LICENSE.md

"""
Test Python altastata with HPCS user (same pattern as notebook first cell).
Reads properties from a file, creates AltaStataFunctions with empty password (HPCS),
optionally registers for PyTorch. Run on LinuxONE host with HPCS_API_KEY set.

Usage:
  export HPCS_API_KEY="$(cat ~/.hpcs-api-key)"
  python3 test_python_hpcs_user.py /path/to/hpcs-user-hpcs.properties
  PROPERTIES_FILE=/path/to/hpcs-user-hpcs.properties python3 test_python_hpcs_user.py
"""
import os
import sys

def main():
    if len(sys.argv) >= 2:
        props_path = sys.argv[1]
    else:
        props_path = os.environ.get("PROPERTIES_FILE", "/home/ubuntu/hpcs-user-hpcs.properties")

    if not os.path.isfile(props_path):
        print(f"Properties file not found: {props_path}", file=sys.stderr)
        sys.exit(1)

    with open(props_path, "r") as f:
        user_properties = f.read()

    # HPCS: private key is in HSM
    private_key = ""

    # Same imports as notebook first cell (altastata_functions + PyTorch registration)
    try:
        from altastata.altastata_functions import AltaStataFunctions
    except ImportError:
        from altastata import AltaStataFunctions

    print("Creating AltaStataFunctions from credentials (HPCS user)...")
    altastata_functions = AltaStataFunctions.from_credentials(user_properties, private_key)
    altastata_functions.set_password("")

    try:
        from altastata.altastata_pytorch_dataset import register_altastata_functions_for_pytorch
        register_altastata_functions_for_pytorch(altastata_functions, "hpcs-user")
        print("Registered AltaStataFunctions for PyTorch as 'hpcs-user'.")
    except ImportError as e:
        print(f"PyTorch registration skipped: {e}")

    print("Done. AltaStataFunctions ready (HPCS user).")

if __name__ == "__main__":
    main()
