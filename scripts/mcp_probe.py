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
        self.session_id = None
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(self, method, params=None, notification=False):
        self.counter += 1
        body = {'jsonrpc': '2.0', 'method': method}
        if not notification:
            body['id'] = self.counter
        if params is not None:
            body['params'] = params
        headers = {
            'Authorization': 'Bearer ' + self.token,
            'Content-Type': 'application/json',
            'Accept': 'application/json, text/event-stream',
            'MCP-Protocol-Version': PROTOCOL,
        }
        if self.session_id:
            headers['Mcp-Session-Id'] = self.session_id
        request = urllib.request.Request(self.url, json.dumps(body).encode(), headers=headers)
        with self.opener.open(request, timeout=12) as response:
            self.session_id = response.headers.get('Mcp-Session-Id', self.session_id)
            if response.headers.get_content_type() == 'text/event-stream':
                result = None
                data = []
                for raw_line in response:
                    line = raw_line.decode('utf-8').rstrip('\r\n')
                    if line.startswith('data:'):
                        data.append(line[5:].lstrip())
                    elif not line and data:
                        event = json.loads('\n'.join(data))
                        data = []
                        if event.get('id') == body.get('id') and ('result' in event or 'error' in event):
                            result = event
                            break
                if result is None:
                    raise RuntimeError('MCP response stream closed without a matching reply')
                payload = json.dumps(result).encode('utf-8')
            else:
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
            'clientInfo': {'name': 'gtnhmcp-probe', 'version': '0.2.0'},
        })
        self.request('notifications/initialized', notification=True)
        return result

    def close(self):
        if not self.session_id:
            return
        request = urllib.request.Request(self.url, method='DELETE', headers={
            'Authorization': 'Bearer ' + self.token,
            'Mcp-Session-Id': self.session_id,
            'MCP-Protocol-Version': PROTOCOL,
        })
        try:
            with self.opener.open(request, timeout=5):
                pass
        except urllib.error.HTTPError as error:
            if error.code not in (404, 405):
                raise
        finally:
            self.session_id = None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True, help='Path to .minecraft/config/gtnhmcp.properties')
    parser.add_argument('--list', action='store_true', help='List tools instead of calling one')
    parser.add_argument('--tool', default='get_context')
    parser.add_argument('--arguments', default='{}', help='JSON object of tool arguments')
    args = parser.parse_args()
    client = None
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
    finally:
        if client is not None:
            client.close()


if __name__ == '__main__':
    sys.exit(main())
