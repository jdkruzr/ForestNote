#!/usr/bin/env python3
"""Helper to run commands on the Viwoods tablet via SSH."""
import sys
import os
import warnings
import paramiko

warnings.filterwarnings("ignore")

TABLET_IP = os.environ.get("TABLET_IP", "192.168.68.139")
TABLET_PORT = int(os.environ.get("TABLET_PORT", "8022"))
TABLET_USER = os.environ.get("TABLET_USER", "u0_a155")
TABLET_PASS = os.environ.get("TABLET_PASS", "ehhjqb")

def run(cmd, timeout=30):
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(TABLET_IP, port=TABLET_PORT, username=TABLET_USER, password=TABLET_PASS, timeout=10)
    stdin, stdout, stderr = client.exec_command(cmd, timeout=timeout)
    out = stdout.read().decode()
    err = stderr.read().decode()
    client.close()
    if out:
        print(out, end='')
    if err:
        print(err, end='', file=sys.stderr)

if __name__ == '__main__':
    run(' '.join(sys.argv[1:]))
