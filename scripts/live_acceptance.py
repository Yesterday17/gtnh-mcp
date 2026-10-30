#!/usr/bin/env python3
"""Verify the running GTNH 2.9.0-RC-2 acceptance world using Python's standard library.

The disposable AcceptanceFixture world must already be running. Without --control-dir,
only read-only core checks run. Supplying the fixture's explicit control directory also
pauses, resumes, unloads and rejoins that world. This script never sends a quit command.
Every MCP response, including retries and expected errors, is saved to --output.
"""
import argparse
import json
import os
import pathlib
import sys
import tempfile
import time

from mcp_probe import Client, read_config


MAX_PAGES = 80
WAIT_SECONDS = 60
CHEST = {'x': 2, 'y': 65, 'z': 0}
FURNACE = {'x': 4, 'y': 65, 'z': 0}
PLAYER_ITEMS = {'minecraft:iron_ingot': 32, 'minecraft:redstone': 16, 'minecraft:diamond': 3}
CHEST_ITEMS = {'minecraft:iron_ingot': 64, 'minecraft:redstone': 32, 'minecraft:log': 16}
TOOLS = {'get_context', 'get_inventory', 'inspect_target', 'inspect_machine', 'find_machines',
         'search_items', 'find_recipes', 'find_usages', 'get_recipe'}


class Report:
    def __init__(self, output, lifecycle):
        self.output = output
        self.token = ''
        self.data = {'targetPack': '2.9.0-RC-2', 'startedAtEpochMillis': int(time.time() * 1000),
                     'status': 'running', 'lifecycleRequested': lifecycle, 'records': [], 'checks': []}

    def redact(self, text):
        return text.replace(self.token, '<redacted>') if self.token else text

    def save(self):
        payload = self.redact(json.dumps(self.data, ensure_ascii=False, indent=2))
        self.output.write_text(payload + '\n', encoding='utf-8')

    def record(self, entry):
        self.data['records'].append(entry)
        self.save()

    def check(self, name, condition):
        self.data['checks'].append({'name': name, 'passed': bool(condition)})
        self.save()
        if not condition:
            raise AssertionError(name)


class RecordingClient(Client):
    def __init__(self, url, token, report):
        super().__init__(url, token)
        self.report = report

    def request(self, method, params=None, notification=False):
        started = time.monotonic()
        record = {'method': method, 'params': params, 'notification': notification}
        try:
            result = super().request(method, params, notification)
            record['result'] = result
            return result
        except Exception as error:
            record['exception'] = {'type': type(error).__name__, 'message': str(error)}
            raise
        finally:
            record['elapsedSeconds'] = round(time.monotonic() - started, 3)
            self.report.record(record)


class Acceptance:
    def __init__(self, client, report, control_dir):
        self.client, self.report, self.control_dir = client, report, control_dir

    def call(self, name, arguments=None, allow_error=False):
        result = self.client.request('tools/call', {'name': name, 'arguments': arguments or {}})
        if not isinstance(result, dict):
            raise AssertionError(name + ' did not return a tool result')
        if result.get('isError'):
            if allow_error:
                return result
            raise AssertionError(name + ': ' + json.dumps(result, ensure_ascii=False))
        if allow_error:
            return result
        data = result.get('structuredContent')
        if not isinstance(data, dict):
            raise AssertionError(name + ' did not return structuredContent')
        return data

    def check(self, name, condition):
        self.report.check(name, condition)

    @staticmethod
    def error(result):
        return result.get('structuredContent', {}).get('error', {})

    def wait_nei(self):
        deadline = time.monotonic() + WAIT_SECONDS
        while True:
            result = self.call('search_items', {'query': 'minecraft:iron_ingot', 'limit': 5}, True)
            if not result.get('isError'):
                return result['structuredContent']
            error = self.error(result)
            if not (error.get('code') == 'game_request_failed'
                    and error.get('message', '').startswith('NEI item loading is not complete.')):
                raise AssertionError('Unexpected NEI readiness error: ' + json.dumps(result))
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise AssertionError('NEI item loading did not finish within 60 seconds')
            time.sleep(min(2, remaining))
            if time.monotonic() >= deadline:
                raise AssertionError('NEI item loading did not finish within 60 seconds')

    def check_item_search(self, data):
        offset = 0
        for _ in range(MAX_PAGES):
            if any(item.get('registryName') == 'minecraft:iron_ingot' and item.get('meta') == 0
                   for item in data['items']):
                self.check('NEI search returns iron ingot meta 0', True)
                return
            next_offset = data.get('nextOffset')
            if not data.get('hasMore') or not isinstance(next_offset, int) or next_offset <= offset:
                break
            offset = next_offset
            data = self.call('search_items', {'query': 'minecraft:iron_ingot', 'offset': offset, 'limit': 5})
        raise AssertionError('Iron ingot not found within the bounded NEI item search')

    def find_detail(self, tool, registry, recipe_map, predicate=None):
        arguments = {'registryName': registry, 'meta': 0, 'limit': 30}
        for _ in range(MAX_PAGES):
            data = self.call(tool, arguments)
            for recipe in data['recipes']:
                if recipe.get('gregtech', {}).get('recipeMapId') == recipe_map:
                    detail = self.call('get_recipe', {'recipeId': recipe['recipeId']})
                    if predicate is None or predicate(detail):
                        return detail
            cursor = data.get('cursor')
            if not data.get('hasMore') or not cursor:
                break
            # The cursor identifies mutable server-side progress and may repeat across pages.
            arguments = {'cursor': cursor, 'limit': 30}
        raise AssertionError('Matching recipe not found within %d pages: %s' % (MAX_PAGES, recipe_map))

    def inventory(self, snapshot, expected, label):
        self.check(label + ' is authoritative', snapshot.get('source') == 'integrated_server')
        inventory = snapshot['inventory']
        self.check(label + ' snapshot is complete', inventory.get('complete') is True)
        counts = {}
        for slot in inventory['slots']:
            item = slot['item']
            if item.get('meta') == 0:
                name = item['registryName']
                counts[name] = counts.get(name, 0) + item['count']
        for name, count in expected.items():
            self.check('%s contains exactly %d %s (meta 0)' % (label, count, name), counts.get(name) == count)

    @staticmethod
    def outputs(recipe):
        result = [recipe['result']] if recipe.get('result') else []
        return result + recipe.get('otherSlots', [])

    @staticmethod
    def alternatives(slots):
        return [item for slot in slots for item in slot.get('alternatives', [])]

    def is_iron_ingot_solidification(self, recipe):
        # GT's native NEI lookup expands familiar ore prefixes (e.g. ingot -> nugget).
        # Select by the actual recipe data, not merely its map or the queried item.
        iron_output = any(item.get('registryName') == 'minecraft:iron_ingot' and item.get('meta') == 0
                          and item.get('amount') == 1
                          for item in self.alternatives(self.outputs(recipe)))
        fluid_input = any(item.get('kind') == 'fluid' and item.get('fluidName') == 'molten.iron'
                          and item.get('amount') == 144 and item.get('unit') == 'mB'
                          for item in self.alternatives(recipe['ingredients']))
        return iron_output and fluid_input

    def core(self):
        self.client.initialize()
        listed = self.client.request('tools/list')
        self.check('all nine expected tools are registered', {tool['name'] for tool in listed['tools']} == TOOLS)
        context = self.call('get_context')
        self.check('RC2 singleplayer world is loaded', context.get('targetPack') == '2.9.0-RC-2'
                   and context.get('mode') == 'singleplayer' and context.get('worldLoaded') is True)
        self.check('RC2 NEI version matches', context['installedMods'].get('NotEnoughItems') == '2.8.155-GTNH')
        self.check('RC2 GregTech version matches', context['installedMods'].get('gregtech') == '5.09.54.205')
        session = context['worldSessionId']
        self.check('world has a session identity', bool(session) and session != 'no_world')
        self.inventory(self.call('get_inventory'), PLAYER_ITEMS, 'Player inventory')
        self.check_item_search(self.wait_nei())

        solidifier = self.find_detail('find_recipes', 'minecraft:iron_ingot', 'gt.recipe.fluidsolidifier',
                                     self.is_iron_ingot_solidification)
        inputs = solidifier['ingredients']
        self.check('solidifier mold is non-consumed with quantity zero', any(
            slot.get('nonConsumed') is True and any(item.get('amount') == 0 and item.get('unit') == 'item'
                                                    for item in slot.get('alternatives', [])) for slot in inputs))
        self.check('solidifier consumes 144 mB molten iron', any(item.get('kind') == 'fluid'
                   and item.get('fluidName') == 'molten.iron'
                   and item.get('amount') == 144 and item.get('unit') == 'mB'
                   for item in self.alternatives(inputs)))
        self.check('solidifier produces one iron ingot', any(item.get('registryName') == 'minecraft:iron_ingot'
                   and item.get('amount') == 1 and item.get('meta') == 0
                   for item in self.alternatives(self.outputs(solidifier))))
        self.check('solidifier duration is 32 ticks', solidifier['gregtech'].get('durationTicks') == 32)

        centrifuge = self.find_detail('find_usages', 'minecraft:soul_sand', 'gt.recipe.centrifuge')
        outputs = self.outputs(centrifuge)
        chances = {slot.get('chance') for slot in outputs if slot.get('role') == 'output'
                   and slot.get('chanceScale') == 10000 and slot.get('chanceMeaning') == 'produced'}
        self.check('centrifuge output probabilities preserve 1000/700/9000 of 10000',
                   {1000, 700, 9000}.issubset(chances))
        self.check('centrifuge produces 200 mB fluid', any(item.get('kind') == 'fluid'
                   and item.get('amount') == 200 and item.get('unit') == 'mB'
                   for item in self.alternatives(outputs)))
        self.check('centrifuge duration and EU/t are preserved', centrifuge['gregtech'].get('durationTicks') == 200
                   and centrifuge['gregtech'].get('euPerTick') == 12)

        nearby = self.call('find_machines', {'radius': 32, 'limit': 100})
        self.check('nearby scan is authoritative', nearby.get('source') == 'integrated_server')
        for label, position in [('chest', CHEST), ('furnace', FURNACE)]:
            self.check('nearby scan includes fixture ' + label,
                       any(all(tile.get(key) == value for key, value in position.items()) for tile in nearby['tiles']))
        chest = self.call('inspect_machine', CHEST)
        self.check('explicit fixture target is a chest', chest.get('registryName') == 'minecraft:chest'
                   and all(chest.get(key) == value for key, value in CHEST.items()))
        self.inventory(chest, CHEST_ITEMS, 'Inspected chest')
        self.inventory(self.call('get_inventory', dict(CHEST, scope='container')), CHEST_ITEMS, 'Chest inventory')
        target = self.call('inspect_target')
        self.check('crosshair resolves the fixture chest', target.get('registryName') == 'minecraft:chest'
                   and all(target.get(key) == value for key, value in CHEST.items()))
        self.check('crosshair snapshot is authoritative', target.get('source') == 'integrated_server')
        return session, centrifuge['recipeId']

    def read_status(self):
        try:
            return json.loads((self.control_dir / 'status.json').read_text(encoding='utf-8'))
        except FileNotFoundError:
            return None

    def wait_status(self, phase, issued_ms, previous_updated=-1, command=None):
        deadline = time.monotonic() + WAIT_SECONDS
        last_seen = None
        while time.monotonic() < deadline:
            status = self.read_status()
            if status is not None and status != last_seen:
                self.report.record({'fixtureStatus': status, 'waitingForPhase': phase, 'command': command})
                last_seen = status
            if status:
                updated = status.get('updatedAtEpochMillis', -1)
                fresh = isinstance(updated, (int, float)) and updated >= issued_ms and updated > previous_updated
                if fresh and status.get('phase') == 'failed':
                    raise AssertionError('Acceptance fixture reports failure; inspect the game log')
                matched = fresh and status.get('phase') == phase
                if command is not None:
                    matched = matched and status.get('lastCommand') == command
                if phase in ('world_ready', 'paused'):
                    matched = matched and status.get('ready') is True and status.get('worldLoaded') is True
                    matched = matched and status.get('paused') is (phase == 'paused')
                elif phase == 'unloaded':
                    matched = matched and status.get('worldLoaded') is False and status.get('ready') is False
                if matched:
                    return status
            time.sleep(min(0.2, max(0, deadline - time.monotonic())))
        raise AssertionError('No fresh fixture status for %s/%s within 60 seconds' % (command, phase))

    def command(self, command, phase):
        previous = self.read_status() or {}
        issued_ms = int(time.time() * 1000)
        # Publish a complete marker atomically, refusing to overwrite another pending command.
        with tempfile.NamedTemporaryFile(mode='w', dir=self.control_dir, prefix='command-',
                                         suffix='.tmp', delete=False, encoding='utf-8') as pending:
            pending.write(command + '\n')
            pending_path = pathlib.Path(pending.name)
        try:
            os.link(pending_path, self.control_dir / 'command.txt')
        finally:
            pending_path.unlink()
        self.report.record({'fixtureCommand': command, 'issuedAtEpochMillis': issued_ms})
        return self.wait_status(phase, issued_ms, previous.get('updatedAtEpochMillis', -1), command)

    def lifecycle(self, old_session, old_recipe_id):
        self.command('pause', 'paused')
        paused = self.call('get_inventory', allow_error=True)
        self.check('paused inventory returns world_paused', paused.get('isError') is True
                   and self.error(paused).get('code') == 'world_paused')
        self.command('resume', 'world_ready')
        self.inventory(self.call('get_inventory'), PLAYER_ITEMS, 'Resumed player inventory')
        resumed = self.call('get_context')
        self.check('resume keeps the same world session', resumed.get('worldSessionId') == old_session
                   and resumed.get('paused') is False)
        old_recipe = self.call('get_recipe', {'recipeId': old_recipe_id})
        self.command('unload', 'unloaded')
        unloaded = self.call('get_context')
        self.check('unload reports no loaded world', unloaded.get('worldLoaded') is False
                   and unloaded.get('mode') == 'menu' and unloaded.get('worldSessionId') == 'no_world')
        self.command('rejoin', 'world_ready')
        rejoined = self.call('get_context')
        self.check('rejoin has a new world session', rejoined.get('worldLoaded') is True
                   and rejoined.get('mode') == 'singleplayer'
                   and rejoined.get('worldSessionId') not in (None, '', 'no_world', old_session))
        self.inventory(self.call('get_inventory'), PLAYER_ITEMS, 'Rejoined player inventory')
        expired = self.call('get_recipe', {'recipeId': old_recipe_id}, allow_error=True)
        self.check('old recipe ID invalidates on world change', expired.get('isError') is True
                   and self.error(expired).get('code') == 'game_request_failed'
                   and 'Recipe ID expired or unknown' in self.error(expired).get('message', ''))
        age_ms = int(time.time() * 1000) - old_recipe['capturedAtEpochMillis']
        self.check('recipe invalidation was observed before natural TTL expiry',
                   age_ms < old_recipe['cacheTtlSeconds'] * 1000)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True, type=pathlib.Path, help='Running instance gtnhmcp.properties')
    parser.add_argument('--output', required=True, type=pathlib.Path, help='JSON evidence file (no token is saved)')
    parser.add_argument('--control-dir', type=pathlib.Path,
                        help='Explicit AcceptanceFixture directory; enables pause/resume/unload/rejoin')
    args = parser.parse_args()
    output = args.output.resolve()
    control_dir = args.control_dir.resolve() if args.control_dir else None
    reserved = {args.config.resolve()}
    if control_dir:
        if not control_dir.is_dir():
            parser.error('--control-dir must already be an AcceptanceFixture directory')
        reserved.update({control_dir / 'command.txt', control_dir / 'status.json', control_dir / 'status.json.tmp'})
    if output in reserved:
        parser.error('--output must not overwrite the config or fixture control files')
    output.parent.mkdir(parents=True, exist_ok=True)
    report = Report(output, control_dir is not None)
    try:
        url, token = read_config(args.config)
        report.token = token
        report.save()
        acceptance = Acceptance(RecordingClient(url, token, report), report, control_dir)
        if control_dir:
            acceptance.wait_status('world_ready', int(time.time() * 1000))
        session, recipe_id = acceptance.core()
        if control_dir:
            acceptance.lifecycle(session, recipe_id)
        report.data['status'] = 'passed'
        report.data['lifecycleStatus'] = 'passed' if control_dir else 'not_requested'
        print('Live acceptance passed (%s); evidence: %s' %
              ('core and lifecycle' if control_dir else 'core only', output))
        return 0
    except Exception as error:
        report.data['status'] = 'failed'
        report.data['failure'] = {'type': type(error).__name__, 'message': str(error)}
        print(report.redact('Live acceptance failed: ' + str(error)), file=sys.stderr)
        return 1
    finally:
        report.data['finishedAtEpochMillis'] = int(time.time() * 1000)
        report.save()


if __name__ == '__main__':
    sys.exit(main())
