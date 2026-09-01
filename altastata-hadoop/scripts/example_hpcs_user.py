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
Example: use AltaStata with HPCS user properties on LinuxONE.
Pattern matches altastata-python-package/fsspec-example/ (from_credentials + create_filesystem + fs.ls).
Reads properties from a file (e.g. /home/ubuntu/hpcs-user-hpcs.properties),
creates AltaStataFunctions, sets password (empty for HPCS), lists Public/.
Usage:
  python3 example_hpcs_user.py /path/to/hpcs-user-hpcs.properties
  PROPERTIES_FILE=/path/to/hpcs-user-hpcs.properties python3 example_hpcs_user.py
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

    # HPCS users: private key is in HSM, so pass empty or placeholder
    private_key = ""

    from altastata import AltaStataFunctions
    from altastata.fsspec import create_filesystem

    print("Creating AltaStataFunctions from credentials...")
    altastata_functions = AltaStataFunctions.from_credentials(user_properties, private_key)
    # HPCS/passwordless: empty password
    altastata_functions.set_password("")
    print("Creating fsspec filesystem...")
    fs = create_filesystem(altastata_functions, "hpcs-user")
    print("Listing Public/ ...")

    try:
        entries = fs.ls("Public/")
        print(f"Public/ has {len(entries)} item(s):")
        for e in entries[:20]:
            print(f"  {e}")
        if len(entries) > 20:
            print(f"  ... and {len(entries) - 20} more")
    except Exception as ex:
        print(f"fs.ls failed: {ex}")
        raise

    print("Done.")

if __name__ == "__main__":
    main()
