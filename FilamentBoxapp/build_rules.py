"""Generate reviewable Realtime Database rules; this script never deploys them."""
import json
from pathlib import Path

member = "auth != null && root.child('filamentBox/miembros').child(auth.uid).val() == true"
device = "auth != null && root.child('filamentBox/dispositivos').child(auth.uid).val() == true"

def number(low, high):
    return {'.validate': f'newData.isNumber() && newData.val() >= {low} && newData.val() <= {high}'}

def settings():
    return {'.validate': "newData.hasChildren(['targetRh','hysteresis','uvAlert'])",
            'targetRh': number(10, 60), 'hysteresis': number(1, 20), 'uvAlert': number(0, 11),
            '$other': {'.validate': False}}

def climate():
    return {'.validate': "newData.hasChild('valid') && (newData.child('valid').val() == false || newData.hasChildren(['t','rh']))",
            'valid': {'.validate': 'newData.isBoolean()'}, 't': number(0, 50), 'rh': number(0, 100),
            '$other': {'.validate': False}}

def telemetry():
    node = {'.validate': "newData.hasChildren(['sampleId','sampleTs','sampleUptimeMs','chamber','outdoor','postSilica','uv','uvUnit'])"}
    for key in ['chamber', 'outdoor', 'postSilica']:
        node[key] = climate()
    for key in ['power', 'auto', 'fan', 'inletOpen', 'outletExterior', 'positionKnown', 'clockSynced']:
        node[key] = {'.validate': 'newData.isBoolean()'}
    for key in ['sampleTs', 'sampleUptimeMs', 'receivedAt', 'droppedSamples', 'queueCapacity']:
        node[key] = number(0, 9007199254740991)
    for key in ['sampleId', 'silica', 'wifi', 'mode', 'type']:
        node[key] = {'.validate': 'newData.isString() && newData.val().length <= 100'}
    node['uv'] = number(0, 3.6)
    node['uvRaw'] = number(0, 4095)
    node['uvUnit'] = {'.validate': "newData.val() == 'V'"}
    node['settings'] = settings()
    node['alerts'] = {'$index': {'.validate': 'newData.isString() && newData.val().length <= 200'}}
    node['$other'] = {'.validate': False}
    return node

control = {'.read': f'({member}) || ({device})', '.write': f'({member}) && newData.exists()',
           '.validate': "newData.hasChildren(['power','auto','fan','inletOpen','outletExterior','mode','settings','issuedAt'])"}
for key in ['power', 'auto', 'fan', 'inletOpen', 'outletExterior']:
    control[key] = {'.validate': 'newData.isBoolean()'}
control['mode'] = {'.validate': "newData.isString() && (newData.val() == 'OFF' || newData.val() == 'AUTO' || newData.val() == 'STORAGE' || newData.val() == 'RECIRC' || newData.val() == 'PURGE' || newData.val() == 'MAINT')"}
control['issuedAt'] = {'.validate': 'newData.isNumber() && newData.val() > now - 30000 && newData.val() <= now + 5000'}
control['settings'] = settings()
control['$other'] = {'.validate': False}
sensors = telemetry() | {'.read': member, '.write': f'({device}) && newData.exists()'}
samples = {'.read': member, '.indexOn': ['sampleTs'], '$sample': telemetry() | {'.write': f'({device}) && newData.exists()'}}
reading = {'.validate': "newData.hasChildren(['ts','mode','uv'])", 'ts': number(0, 9007199254740991),
           'mode': {'.validate': 'newData.isString() && newData.val().length <= 12'}, 'uv': number(0, 3.6)}
for key in ['chT', 'outT', 'postT']:
    reading[key] = number(0, 50)
for key in ['chRh', 'outRh', 'postRh']:
    reading[key] = number(0, 100)
reading['$other'] = {'.validate': False}
own = 'auth != null && auth.uid == $uid'
rules = {'rules': {'.read': False, '.write': False, 'filamentBox': {
    'miembros': {'$uid': {'.read': own, '.write': False}},
    'dispositivos': {'.read': False, '.write': False},
    'control': control, 'sensores': sensors, 'muestras': samples,
    'historial': {'$uid': {'.read': own, '.write': own, '.indexOn': ['ts'], '$sample': reading}},
    'usuarios': {'$uid': {'.read': own, '.write': own,
        'nombre': {'.validate': 'newData.isString() && newData.val().length <= 100'},
        'createdAt': number(0, 9007199254740991), '$other': {'.validate': False}}}
}}}
Path(__file__).with_name('database.rules.json').write_text(json.dumps(rules, indent=2) + '\n', encoding='utf-8')
print('Rules generated locally; deployment and emulator authorization tests remain separate.')
