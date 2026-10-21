"""Per-seed final state (private, public, speaking, edges with weights) from a harness log."""
import re, sys, json, collections
cur = None
out = collections.defaultdict(dict)
last_speaking = {}
for line in open(sys.argv[1], encoding='utf-8', errors='replace'):
    m = re.search(r'=== RUN \d+ spec=(\S+) rep=(\d+)', line)
    if m:
        cur = (m.group(1), int(m.group(2)))
        continue
    m = re.search(r'SIM: Speaking\((.*)\)', line)
    if m and cur:
        last_speaking[cur] = [int(x) for x in m.group(1).split(',')]
    m = re.search(r'=== RESULT spec=(\S+) rep=(\d+) finalRound=(\d+) consensus=(\w+) private=\[([^\]]*)\] public=\[([^\]]*)\]', line)
    if m:
        k = (m.group(1), int(m.group(2)))
        out[k[0]][k[1]] = {'round': int(m.group(3)), 'consensus': m.group(4) == 'true',
                           'private': [float(x) for x in m.group(5).split(',')],
                           'public': [float(x) for x in m.group(6).split(',')], 'speaking': last_speaking.get(k)}
    m = re.search(r'=== TOPOLOGY spec=(\S+) rep=(\d+) edges=(.*)$', line)
    if m:
        k = (m.group(1), int(m.group(2)))
        out[k[0]][k[1]]['edges'] = [(int(a), int(b), float(w)) for a, b, w in
                                    (re.match(r'(\d+)<-(\d+):([\d.]+)', e).groups() for e in m.group(3).split())]
json.dump(out, open(sys.argv[2], 'w'), indent=1)
for spec, seeds in out.items():
    print('##', spec)
    for s, v in sorted(seeds.items()):
        print(f"  seed {s:2d} r={v['round']:3d} cons={int(v['consensus'])} priv={[round(x, 2) for x in v['private']]} spk={v['speaking']}")
