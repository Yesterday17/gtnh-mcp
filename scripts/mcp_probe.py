#!/usr/bin/env python3
"""Connect to an already running GTNH MCP mod. Python 3 standard library only."""
import argparse
import json
import pathlib
import sys
import urllib.error
import urllib.request

PROTOCOL = '2025-11-25'


def read_config(path):
    properties = {}
    for line in pathlib.Path(path).read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if line and not line.startswith(('#', '!')) and '=' in line:
            key, value = line.split('=', 1)
            properties[key.strip()] = value.strip()
    token = properties.get('token', '')
    if len(token) < 32:
        raise ValueError('No valid token in the selected config file')
    return 'http://127.0.0.1:' + properties.get('port', '29710') + '/mcp', token


class Client:
    def __init__(self, url, token):
        self.url, self.token, self.counter = url, token, 0
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(self, method, params=None, notification=False):
        self.counter += 1
        body = {'jsonrpc': '2.0', 'method': method}
        if not notification:
            body['id'] = self.counter
        if params is not None:
            body['params'] = params
        request = urllib.request.Request(self.url, json.dumps(body).encode(), headers={
            'Authorization': 'Bearer ' + self.token,
            'Content-Type': 'application/json',
            'Accept': 'application/json, text/event-stream',
            'MCP-Protocol-Version': PROTOCOL,
        })
        with self.opener.open(request, timeout=12) as response:
            payload = response.read()
        if not payload:
            return None
        result = json.loads(payload)
        if 'error' in result:
            raise RuntimeError('MCP error: ' + json.dumps(result['error'], ensure_ascii=False))
        return result.get('result')

    def initialize(self):
        result = self.request('initialize', {
            'protocolVersion': PROTOCOL,
            'capabilities': {},
            'clientInfo': {'name': 'gtnhmcp-probe', 'version': '0.1.0'},
        })
        self.request('notifications/initialized', notification=True)
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True, help='Path to .minecraft/config/gtnhmcp.properties')
    parser.add_argument('--list', action='store_true', help='List tools instead of calling one')
    parser.add_argument('--tool', default='get_context')
    parser.add_argument('--arguments', default='{}', help='JSON object of tool arguments')
    args = parser.parse_args()
    try:
        url, token = read_config(args.config)
        client = Client(url, token)
        client.initialize()
        arguments = json.loads(args.arguments)
        if not isinstance(arguments, dict):
            raise ValueError('--arguments must be a JSON object')
        result = client.request('tools/list') if args.list else client.request('tools/call', {'name': args.tool, 'arguments': arguments})
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 2 if isinstance(result, dict) and result.get('isError') else 0
    except (ValueError, OSError, RuntimeError, urllib.error.URLError) as error:
        print('Probe failed: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
