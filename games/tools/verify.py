#!/usr/bin/env python3
"""Checks built JARs: manifest, icon, MIDlet class present, no permissions requested."""
import zipfile, os, sys, glob

root = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
dist = os.path.join(root, 'dist')
bad = 0
rows = []
for jar in sorted(glob.glob(os.path.join(dist, '*.jar'))):
    gid = os.path.basename(jar)[:-4]
    z = zipfile.ZipFile(jar)
    names = z.namelist()
    mf = z.read('META-INF/MANIFEST.MF').decode('utf-8', 'replace')
    mf = mf.replace('\r\n', '\n').replace('\n ', '')  # long manifest lines are wrapped
    fields = {}
    for line in mf.splitlines():
        if ':' in line:
            k, v = line.split(':', 1)
            fields[k.strip()] = v.strip()
    problems = []
    midlet = fields.get('MIDlet-1', '')
    cls = midlet.split(',')[-1].strip()
    if not cls:
        problems.append('missing MIDlet-1')
    elif cls.replace('.', '/') + '.class' not in names:
        problems.append('missing class ' + cls)
    if 'icon.png' not in names:
        problems.append('missing icon')
    if fields.get('MIDlet-Icon', '') != '/icon.png':
        problems.append('icon not declared')
    for need in ('MIDlet-Name', 'MIDlet-Vendor', 'MIDlet-Version'):
        if not fields.get(need):
            problems.append('missing ' + need)
    perms = fields.get('MIDlet-Permissions', '').strip()
    if perms:
        problems.append('requests permissions: ' + perms)
    jad = jar[:-4] + '.jad'
    if os.path.exists(jad):
        jadtxt = open(jad, encoding='utf-8').read()
        size = os.path.getsize(jar)
        if ('MIDlet-Jar-Size: %d' % size) not in jadtxt:
            problems.append('wrong JAR size in JAD')
    else:
        problems.append('missing JAD')
    classes = len([n for n in names if n.endswith('.class')])
    rows.append((gid, fields.get('MIDlet-Name', '?'), fields.get('MIDlet-Vendor', '?'),
                 os.path.getsize(jar), classes, problems))
    if problems:
        bad += 1

print("%-10s %-12s %-16s %8s %7s  %s" % ('file', 'name', 'vendor', 'size', 'classes', 'problems'))
for gid, name, vendor, size, classes, problems in rows:
    print("%-10s %-12s %-16s %8d %7d  %s" % (gid, name, vendor, size, classes,
          'ok' if not problems else '; '.join(problems)))
print()
print("games:", len(rows), "| with problems:", bad)
sys.exit(1 if bad else 0)
