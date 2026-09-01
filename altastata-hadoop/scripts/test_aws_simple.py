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
Simple AWS AltaStata test (no HPCS).

Reads account properties and the encrypted private key from local files
(not from this script). Same flow as the notebook: from_credentials,
set_password, create_filesystem, fs.ls(Public/).

Usage:
  python3 test_aws_simple.py /path/to/account.user.properties [/path/to/private.key]
  PROPERTIES_FILE=... PRIVATE_KEY_FILE=... python3 test_aws_simple.py

Defaults (if no args / env):
  ~/.altastata/accounts/amazon.rsa.bob123/*.user.properties
  ~/.altastata/accounts/amazon.rsa.bob123/private.key
  ALTASTATA_PASSWORD, else 123
"""
import glob
import os
import sys


def _default_account_dir():
    return os.path.join(os.path.expanduser("~"), ".altastata", "accounts", "amazon.rsa.bob123")


def _resolve_properties_path(explicit):
    if explicit:
        path = os.path.expanduser(explicit)
        if os.path.isdir(path):
            matches = sorted(glob.glob(os.path.join(path, "*.user.properties")))
            if not matches:
                print(f"No *.user.properties in account dir: {path}", file=sys.stderr)
                sys.exit(1)
            return matches[0]
        return path
    account_dir = _default_account_dir()
    matches = sorted(glob.glob(os.path.join(account_dir, "*.user.properties")))
    if matches:
        return matches[0]
    print(
        "Properties file not found. Pass the path or set PROPERTIES_FILE "
        f"(looked in {account_dir}).",
        file=sys.stderr,
    )
    sys.exit(1)


def _resolve_private_key_path(explicit, properties_path):
    if explicit:
        return os.path.expanduser(explicit)
    sibling = os.path.join(os.path.dirname(properties_path), "private.key")
    if os.path.isfile(sibling):
        return sibling
    account_key = os.path.join(_default_account_dir(), "private.key")
    if os.path.isfile(account_key):
        return account_key
    print(
        "Private key file not found. Pass it as the second argument or set PRIVATE_KEY_FILE.",
        file=sys.stderr,
    )
    sys.exit(1)


def main():
    props_arg = sys.argv[1] if len(sys.argv) >= 2 else os.environ.get("PROPERTIES_FILE")
    key_arg = sys.argv[2] if len(sys.argv) >= 3 else os.environ.get("PRIVATE_KEY_FILE")

    props_path = _resolve_properties_path(props_arg)
    key_path = _resolve_private_key_path(key_arg, props_path)

    if not os.path.isfile(props_path):
        print(f"Properties file not found: {props_path}", file=sys.stderr)
        sys.exit(1)
    if not os.path.isfile(key_path):
        print(f"Private key file not found: {key_path}", file=sys.stderr)
        sys.exit(1)

    with open(props_path, "r") as f:
        user_properties = f.read()
    with open(key_path, "r") as f:
        private_key = f.read()

    password = os.environ.get("ALTASTATA_PASSWORD", os.environ.get("PASSWORD", "123"))
    account_name = os.environ.get("ALTA_ACCOUNT_NAME", "bob123").strip() or "bob123"

    try:
        from altastata import AltaStataFunctions
    except ImportError:
        from altastata.altastata_functions import AltaStataFunctions  # type: ignore

    try:
        from altastata.fsspec import create_filesystem
    except ImportError as e:
        print(f"ERROR: altastata.fsspec not available in this environment: {e}", file=sys.stderr)
        sys.exit(1)

    print(f"Creating AltaStataFunctions from {props_path} ...")
    altastata_functions = AltaStataFunctions.from_credentials(user_properties, private_key)
    print(f"Setting password (length={len(password)}) ...")
    altastata_functions.set_password(password)

    print(f"Creating fsspec filesystem for account '{account_name}' ...")
    fs = create_filesystem(altastata_functions, account_name)

    test_path = os.environ.get("ALTA_TEST_PATH", "Public/").strip() or "Public/"
    print(f"Listing {test_path!r} ...")
    try:
        entries = fs.ls(test_path)
        print(f"{test_path} has {len(entries)} item(s):")
        for e in entries[:50]:
            print(f"  {e}")
        if len(entries) > 50:
            print(f"  ... and {len(entries) - 50} more")
    except Exception as ex:
        print(f"fs.ls({test_path!r}) failed: {ex}", file=sys.stderr)
        raise

    print("AWS AltaStata test completed successfully.")


if __name__ == "__main__":
    main()
