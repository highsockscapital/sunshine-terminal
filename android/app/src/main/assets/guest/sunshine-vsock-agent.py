#!/usr/bin/env python3
"""Sunshine vsock exec agent — runs as root inside the Debian guest.

Listens on AF_VSOCK (CID_ANY, port 5000) and executes one framed command per
connection. No sshd, no network stack, no keys on this path: the hypervisor
memory bus is the transport, and only the host that started the VM can
connect.

Request wire format (same bytes as the SSH path's sunshine-exec stdin):
    [u32 BE totalLen][u64 BE blockId][payload "token\\norigin\\ncommand"]
Response wire format (see VsockWire.kt):
    [u32 BE totalLen][i32 BE exitCode][u32 BE outLen][stdout][u32 BE errLen][stderr]

Auth: token must match /run/sunshine/session-token (0600, written by
provision.sh). Execution itself delegates to /usr/local/bin/sunshine-exec,
which re-checks catastrophic patterns as defense in depth.

Run `python3 sunshine-vsock-agent.py --selftest` to verify framing against
the golden vectors shared with VsockWireTest (also run in CI).
"""
import socket
import struct
import subprocess
import sys

PORT = 5000
TOKEN_FILE = "/run/sunshine/session-token"
EXEC_SHIM = "/usr/local/bin/sunshine-exec"
EXEC_TIMEOUT_SEC = 55
FRAME_MAX = 8 * 1024 * 1024

# Golden vector: exitCode=0, stdout="hi\\n", stderr="" (see VsockWire.kt).
GOLDEN_RESPONSE = bytes.fromhex("0000000f000000000000000368690a00000000")


def read_exact(conn, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("vsock-read-eof")
        buf += chunk
    return bytes(buf)


def parse_request(data):
    if len(data) < 12:
        raise ValueError("frame-too-short")
    (total,) = struct.unpack(">I", data[0:4])
    if total != len(data) - 4:
        raise ValueError("frame-length-mismatch")
    (block_id,) = struct.unpack(">Q", data[4:12])
    payload = data[12:].decode("utf-8")
    lines = payload.split("\n")
    token = lines[0] if len(lines) > 0 else ""
    origin = lines[1] if len(lines) > 1 else "human"
    command = "\n".join(lines[2:]) if len(lines) > 2 else ""
    return block_id, token, origin, command


def encode_response(exit_code, stdout, stderr):
    out = stdout.encode("utf-8")
    err = stderr.encode("utf-8")
    total = 4 + 4 + len(out) + 4 + len(err)
    return struct.pack(">I", total) + struct.pack(">i", exit_code) + \
        struct.pack(">I", len(out)) + out + struct.pack(">I", len(err)) + err


def check_token(token):
    try:
        with open(TOKEN_FILE) as f:
            expected = f.read().strip()
    except OSError:
        return False
    return bool(expected) and token == expected


def run_command(token, origin, command):
    """Delegate to sunshine-exec (same policy shim as the SSH path)."""
    try:
        proc = subprocess.run(
            [EXEC_SHIM],
            input=f"{token}\n{origin}\n{command}".encode("utf-8"),
            capture_output=True,
            timeout=EXEC_TIMEOUT_SEC,
        )
        return proc.returncode, proc.stdout.decode("utf-8", "replace"), \
            proc.stderr.decode("utf-8", "replace")
    except subprocess.TimeoutExpired:
        return 124, "", "vsock-exec-timeout"
    except FileNotFoundError:
        return 127, "", "sunshine-exec missing (provision the guest bundle)"
    except Exception as e:  # noqa: BLE001 — report, never crash the listener
        return 1, "", f"vsock-exec-failed: {e}"


def handle(conn):
    try:
        (total,) = struct.unpack(">I", read_exact(conn, 4))
        if total < 8 or total > FRAME_MAX:
            return
        body = read_exact(conn, total)
        try:
            _, token, origin, command = parse_request(struct.pack(">I", total) + body)
        except ValueError:
            conn.sendall(encode_response(2, "", "malformed-frame"))
            return
        if not command.strip():
            conn.sendall(encode_response(0, "", ""))
            return
        if not check_token(token):
            conn.sendall(encode_response(0, "", "SUNSHINE-AUTH-DENIED"))
            return
        code, out, err = run_command(token, origin, command)
        conn.sendall(encode_response(code, out, err))
    except (ConnectionError, OSError):
        pass


def serve():
    srv = socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM)
    try:
        srv.bind((socket.VMADDR_CID_ANY, PORT))
        srv.listen(8)
        print(f"sunshine-vsock-agent: listening on vsock:{PORT}", flush=True)
        while True:
            conn, _ = srv.accept()
            with conn:
                handle(conn)
    finally:
        srv.close()


def selftest():
    # Golden response vector (shared with VsockWireTest).
    assert encode_response(0, "hi\n", "") == GOLDEN_RESPONSE, "golden response mismatch"
    # Request round-trip.
    payload = "tok\nagent\nls -la".encode()
    req = struct.pack(">I", 8 + len(payload)) + struct.pack(">Q", 7) + payload
    bid, token, origin, command = parse_request(req)
    assert (bid, token, origin, command) == (7, "tok", "agent", "ls -la"), "request parse mismatch"
    # Malformed frames rejected, not executed.
    for bad in (b"\x00\x00", struct.pack(">I", 99) + b"short"):
        try:
            parse_request(bad)
            raise SystemExit(f"parse_request accepted bad frame: {bad!r}")
        except ValueError:
            pass
    print("sunshine-vsock-agent selftest: OK")


if __name__ == "__main__":
    if "--selftest" in sys.argv[1:]:
        selftest()
    else:
        serve()
