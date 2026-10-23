#!/usr/bin/env python3
"""Local test server console client; credentials are supplied via environment."""
import os, socket, struct, sys, time

def command(command):
    with socket.create_connection(('127.0.0.1', int(os.environ.get('FP_TEST_RCON_PORT', '25589'))), timeout=20) as sock:
        def packet(kind, text):
            body = struct.pack('<ii', 1, kind) + text.encode() + b'\0\0'
            sock.sendall(struct.pack('<i', len(body)) + body)
        def read_exact(n):
            result = b''
            while len(result) < n:
                data = sock.recv(n - len(result))
                if not data: raise EOFError('RCON closed')
                result += data
            return result
        def receive():
            length = struct.unpack('<i', read_exact(4))[0]
            data = read_exact(length)
            return struct.unpack('<ii', data[:8]), data[8:-2].decode()
        packet(3, os.environ.get('FP_TEST_RCON_PASSWORD', 'local-fakeplayer-test'))
        header, _ = receive()
        if header[0] == -1: raise RuntimeError('RCON authentication failed')
        packet(2, command)
        return receive()[1]

if __name__ == '__main__':
    for cmd in sys.argv[1:]:
        start = time.monotonic()
        print(cmd, '=>', command(cmd), f'({time.monotonic()-start:.3f}s)', flush=True)
