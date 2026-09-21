"""
Builds the default Bastion setup for CastleDungeon_1.21.11.schem.

Reads the schematic, closes the three archways with portcullis gates (iron bars), finds the rooms by flood fill,
picks spawn points, chest spots and boss points from the real floor plan, and writes:

  Dungeons.schem   the castle with the gates built in (paste this into the Dungeons world)
  setup.yml        regions (spawn, rooms, room cores as polygons that follow the halls, and the DUNGEON box),
                   the three gates, and the points (anchor, mob spawns, chests, altar, boss)
  schematics/      the castle with the gates built in

Everything is data: nothing here is compiled into the plugin. Usage:
  python build_setup.py <schematic> <output dir> [ox oy oz]
"""
import gzip, io, json, os, random, struct, sys
import numpy as np
from scipy import ndimage
from shapely.geometry import box as sbox
from shapely.ops import unary_union

WORLD = "Dungeons"
src, out = sys.argv[1], sys.argv[2]
ox, oy, oz = (int(v) for v in sys.argv[3:6]) if len(sys.argv) >= 6 else (-251, 135, -326)
random.seed(1907)


# ------------------------------------------------------------------ read the schematic
def nbt(f, t):
    if t == 1: return struct.unpack('>b', f.read(1))[0]
    if t == 2: return struct.unpack('>h', f.read(2))[0]
    if t == 3: return struct.unpack('>i', f.read(4))[0]
    if t == 4: return struct.unpack('>q', f.read(8))[0]
    if t == 5: return struct.unpack('>f', f.read(4))[0]
    if t == 6: return struct.unpack('>d', f.read(8))[0]
    if t == 7:
        n = struct.unpack('>i', f.read(4))[0]
        return f.read(n)
    if t == 8:
        n = struct.unpack('>H', f.read(2))[0]
        return f.read(n).decode()
    if t == 9:
        it = f.read(1)[0]
        n = struct.unpack('>i', f.read(4))[0]
        return [nbt(f, it) for _ in range(n)]
    if t == 10:
        r = {}
        while True:
            tt = f.read(1)[0]
            if tt == 0: return r
            n = struct.unpack('>H', f.read(2))[0]
            k = f.read(n).decode()
            r[k] = nbt(f, tt)
    if t == 11:
        n = struct.unpack('>i', f.read(4))[0]
        return list(struct.unpack('>%di' % n, f.read(4 * n)))
    if t == 12:
        n = struct.unpack('>i', f.read(4))[0]
        return f.read(8 * n)


raw = gzip.open(src).read()
f = io.BytesIO(raw)
f.read(1)
f.read(struct.unpack('>H', f.read(2))[0])
S = nbt(f, 10)['Schematic']
W, H, L = S['Width'], S['Height'], S['Length']
palette = [None] * len(S['Blocks']['Palette'])
for k, v in S['Blocks']['Palette'].items():
    palette[v] = k
data = np.frombuffer(S['Blocks']['Data'], dtype=np.uint8)
ends = np.flatnonzero(data < 128)
starts = np.concatenate(([0], ends[:-1] + 1))
vals = data[starts].astype(np.int32) & 0x7f
two = (ends - starts) == 1
vals[two] |= (data[ends[two]].astype(np.int32) & 0x7f) << 7
arr = vals.reshape(H, L, W).astype(np.int32)   # y, z, x
AIR = palette.index('minecraft:air')
base = lambda n: n.split('[')[0].replace('minecraft:', '')


def pal(state):
    if state not in palette:
        palette.append(state)
    return palette.index(state)


# ------------------------------------------------------------------ the gates
# Each gate is a single layer of iron bars filling an archway. plane = the axis that stays fixed.
GATES = {
    'door_room1': dict(x=(155, 155), y=(8, 15), z=(100, 108), axis='x'),   # the archway into the rotunda
    'door_room2': dict(x=(113, 127), y=(8, 18), z=(141, 141), axis='z'),   # rotunda to the south hall
    'door_room3': dict(x=(85, 85), y=(8, 18), z=(100, 108), axis='x'),     # rotunda to the west hall
}
free0 = arr == AIR
gate_cells = {}
for gid, g in GATES.items():
    state = ('minecraft:iron_bars[east=false,north=true,south=true,waterlogged=false,west=false]' if g['axis'] == 'x'
             else 'minecraft:iron_bars[east=true,north=false,south=false,waterlogged=false,west=true]')
    idx = pal(state)
    cells = []
    for y in range(g['y'][0], g['y'][1] + 1):
        for z in range(g['z'][0], g['z'][1] + 1):
            for x in range(g['x'][0], g['x'][1] + 1):
                if arr[y, z, x] == AIR:
                    cells.append((x, y, z))
    gate_cells[gid] = (cells, state)

# ------------------------------------------------------------------ rooms by flood fill, with the gates shut
free = arr == AIR
shut = free.copy()
for cells, _ in gate_cells.values():
    for x, y, z in cells:
        shut[y, z, x] = False
lab, n = ndimage.label(shut)
ROOMS = {'spawn': (200, 9, 100), 'room1': (130, 9, 104), 'room2': (120, 9, 150), 'room3': (60, 9, 104)}
comp = {k: lab == lab[y, z, x] for k, (x, y, z) in ROOMS.items()}
for k, c in comp.items():
    assert c.sum() > 1000, k

extra_barrels = []


def clear(x, z, y=8, r=1, h=3):
    """A spot a mob or player can stand on: floor below, and free space around and above."""
    if arr[y - 1, z, x] == AIR or 'water' in palette[arr[y - 1, z, x]]:
        return False
    win = arr[y:y + h, z - r:z + r + 1, x - r:x + r + 1]
    return bool((win == AIR).all())


def spread(cells, count, gap):
    cells = list(cells)
    random.shuffle(cells)
    chosen = []
    for c in cells:
        if all((c[0] - d[0]) ** 2 + (c[1] - d[1]) ** 2 >= gap * gap for d in chosen):
            chosen.append(c)
        if len(chosen) == count:
            break
    return chosen


def floor_cells(name, x0, x1, z0, z1, r=1):
    c = comp[name]
    return [(x, z) for z in range(z0, z1 + 1) for x in range(x0, x1 + 1) if c[8, z, x] and clear(x, z, r=r)]


# ------------------------------------------------------------------ region polygons
def polygon_of(name, y0=6, y1=None, clip=None):
    c = comp[name]
    ys, zs, xs = np.nonzero(c)
    top = int(ys.max()) if y1 is None else y1
    footprint = c[:top + 1].any(axis=0)
    if clip is not None:
        x0, x1, z0, z1 = clip
        keep = np.zeros_like(footprint)
        keep[z0:z1 + 1, x0:x1 + 1] = True
        footprint = footprint & keep
    squares = [sbox(x, z, x + 1, z + 1) for z, x in zip(*np.nonzero(footprint))]
    shape = unary_union(squares)
    shape = shape.simplify(0.3, preserve_topology=True)
    if shape.geom_type == 'MultiPolygon':
        shape = max(shape.geoms, key=lambda p: p.area)
    pts = list(shape.exterior.coords)[:-1]
    return [(int(round(px)), int(round(pz))) for px, pz in pts], int(ys.max())


regions = {}
for name, rtype in (('spawn', 'SPAWN'), ('room1', 'ROOM1'), ('room2', 'ROOM2'), ('room3', 'ROOM3')):
    pts, top = polygon_of(name)
    regions[name] = (rtype, pts, 6, min(top, 70))

# the heart of each room: the fight starts when everybody is inside it, not merely in the corridor that leads there
CORES = {
    'room1_core': ('room1', (86, 141, 70, 140)),    # the rotunda, well past its archway
    'room2_core': ('room2', (100, 140, 144, 161)),   # the south hall, a few blocks past its gate
    'room3_core': ('room3', (41, 80, 89, 119)),      # the west hall, a few blocks past its gate
}
for cid, (source, clip) in CORES.items():
    pts, top = polygon_of(source, clip=clip)
    regions[cid] = ('OTHER', pts, 6, min(top, 70))

# ------------------------------------------------------------------ points
points = {}


def spot(x, z, y=8, yaw=0.0):
    return "%s %s %s %s %s 0.0" % (WORLD, x + ox + 0.5, y + oy, z + oz + 0.5, yaw)


points['anchor'] = [spot(200, 104, 8, 90.0)]

# room 1: mobs come up in the rotunda, away from the walls
r1 = floor_cells('room1', 92, 146, 74, 134, r=2)
r1 = [c for c in r1 if not (114 <= c[0] <= 126 and 94 <= c[1] <= 114)]        # not on the shrine itself
points['room1'] = [spot(x, z) for x, z in spread(r1, 16, 6)]

# room 2: the south hall, and the altar the mini-boss rises from
r2 = floor_cells('room2', 102, 138, 144, 158, r=2)
points['room2'] = [spot(x, z) for x, z in spread(r2, 12, 4)]
points['altar'] = [spot(108, 150, 9, 0.0)]

# room 3: guards on the carpet, the boss in front of the throne, more boss points to reposition to
r3 = floor_cells('room3', 56, 82, 98, 110, r=1)
guards = [c for c in r3 if 99 <= c[1] <= 109 and 60 <= c[0] <= 76]
GOLD = palette.index('minecraft:raw_gold_block')
gold_cells = [(x, z) for y, z, x in zip(*np.nonzero(arr == GOLD)) if y == 8 and 41 <= x <= 84 and 89 <= z <= 119]
lowered = [(x, 8, z) for x, z in gold_cells]
more_guards = [c for c in spread(guards, 12, 5) if all((c[0] - g[0]) ** 2 + (c[1] - g[1]) ** 2 >= 16 for g in gold_cells)][:6]
points['guards'] = [spot(x, z, yaw=90.0) for x, z in gold_cells + more_guards]
open_spots = [(x, z) for z in range(98, 111) for x in range(55, 84) if clear(x, z, r=1, h=7)]
throne_front = min(open_spots, key=lambda c: (c[0] - 58) ** 2 + (c[1] - 104) ** 2)
boss = [throne_front]
for want in ((66, 99), (66, 109), (76, 104), (60, 99), (60, 109)):
    best = min(open_spots, key=lambda c: (c[0] - want[0]) ** 2 + (c[1] - want[1]) ** 2)
    if all((best[0] - b[0]) ** 2 + (best[1] - b[1]) ** 2 >= 25 for b in boss):
        boss.append(best)
points['boss'] = [spot(x, z, yaw=90.0) for x, z in boss]

# chests: hidden containers in the rotunda. Barrels blend in with the castle. More spots than artifacts.
corner = []
c1 = comp['room1']
for z in range(76, 134):
    for x in range(90, 154):
        if not (c1[8, z, x] and arr[7, z, x] != AIR and clear(x, z, r=0, h=2)):
            continue
        walls = sum(1 for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)) if arr[8, z + dz, x + dx] != AIR)
        if walls >= 1 and not (112 <= x <= 128 and 90 <= z <= 116):
            corner.append((x, z))
placed = spread(corner, 12, 9)
for x, z in placed:
    extra_barrels.append((x, 8, z))
chest_spots = [(x, z) for x, _, z in extra_barrels]
more = [c for c in corner if all((c[0] - d[0]) ** 2 + (c[1] - d[1]) ** 2 >= 49 for d in chest_spots)]
chest_spots += spread(more, 12 - len(chest_spots), 7)
points['chests'] = ["%s %s %s %s 0.0 0.0" % (WORLD, x + ox + 0.5, 8 + oy, z + oz + 0.5) for x, z in chest_spots]

c2 = comp['room2']
corner2 = []
for z in range(144, 160):
    for x in range(101, 140):
        if not (c2[8, z, x] and arr[7, z, x] != AIR and clear(x, z, r=0, h=2)):
            continue
        walls = sum(1 for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)) if arr[8, z + dz, x + dx] != AIR)
        if walls >= 1 and not (115 <= x <= 125):
            corner2.append((x, z))
placed2 = spread(corner2, 8, 6)
for x, z in placed2[:4]:
    extra_barrels.append((x, 8, z))
points['chests_room2'] = ["%s %s %s %s 0.0 0.0" % (WORLD, x + ox + 0.5, 8 + oy, z + oz + 0.5) for x, z in placed2]

# ------------------------------------------------------------------ build the modified schematic
out_arr = arr.copy()
BARREL = pal('minecraft:barrel[facing=up,open=false]')
for x, y, z in extra_barrels:
    out_arr[y, z, x] = BARREL
for x, y, z in lowered:
    out_arr[y - 1, z, x] = GOLD    # sunk into the floor, level with it
    out_arr[y, z, x] = AIR
for gid, (cells, state) in gate_cells.items():
    idx = pal(state)
    for x, y, z in cells:
        out_arr[y, z, x] = idx


def w_str(o, s):
    b = s.encode()
    o.write(struct.pack('>H', len(b)) + b)


def w_named(o, tag, name):
    o.write(bytes([tag]))
    w_str(o, name)


buf = io.BytesIO()
buf.write(bytes([10]))
w_str(buf, "")
w_named(buf, 10, "Schematic")
w_named(buf, 3, "Version"); buf.write(struct.pack('>i', 3))
w_named(buf, 3, "DataVersion"); buf.write(struct.pack('>i', 4671))
w_named(buf, 2, "Width"); buf.write(struct.pack('>h', W))
w_named(buf, 2, "Height"); buf.write(struct.pack('>h', H))
w_named(buf, 2, "Length"); buf.write(struct.pack('>h', L))
w_named(buf, 11, "Offset"); buf.write(struct.pack('>i', 3) + struct.pack('>3i', 0, 0, 0))
w_named(buf, 10, "Blocks")
w_named(buf, 10, "Palette")
for i, name in enumerate(palette):
    w_named(buf, 3, name)
    buf.write(struct.pack('>i', i))
buf.write(b'\x00')
flat = out_arr.reshape(-1)
varints = bytearray()
for v in flat.tolist():
    while v > 127:
        varints.append((v & 127) | 128)
        v >>= 7
    varints.append(v)
w_named(buf, 7, "Data"); buf.write(struct.pack('>i', len(varints)) + bytes(varints))
w_named(buf, 9, "BlockEntities"); buf.write(bytes([10]) + struct.pack('>i', 0))
buf.write(b'\x00\x00\x00')
os.makedirs(out + '/schematics', exist_ok=True)
with open(out + '/schematics/Dungeons.schem', 'wb') as fh:
    fh.write(gzip.compress(buf.getvalue(), 6))

# ------------------------------------------------------------------ the yaml files
def yq(s):
    return '"%s"' % s


with open(out + '/setup.yml', 'w', encoding='utf-8') as fh:
    fh.write("# Bastion setup.yml (default setup for the castle, world %s)\n" % WORLD)
    fh.write("# The whole build: regions, doors and points. World coordinates. The castle's minimum corner is at %d %d %d.\n" % (ox, oy, oz))
    fh.write("# The in-game tools (/dungeon wand, region, door, point, chest) write to this file.\n\n")
    fh.write("regions:\n")
    for name, (rtype, pts, y0, y1) in regions.items():
        fh.write("  %s:\n    type: %s\n    world: %s\n    shape: polygon\n    points:\n" % (name, rtype, WORLD))
        for px, pz in pts:
            fh.write("      - %s\n" % yq("%s,%s" % (px + ox, pz + oz)))
        fh.write("    min-y: %d\n    max-y: %d\n" % (y0 + oy, y1 + oy))
    fh.write("  dungeon:\n    type: DUNGEON\n    world: %s\n    shape: cuboid\n" % WORLD)
    fh.write("    min: [%d, %d, %d]\n    max: [%d, %d, %d]\n" % (ox, oy, oz, ox + W - 1, oy + H - 1, oz + L - 1))

    fh.write("\n# Each door is a portcullis: the bars listed here fill the archway when it is closed.\n")
    fh.write("# Opening lifts them layer by layer from the bottom.\ndoors:\n")
    for gid, g in GATES.items():
        cells, state = gate_cells[gid]
        x0, x1 = g['x'][0] + ox, g['x'][1] + ox
        y0, y1 = g['y'][0] + oy, g['y'][1] + oy
        z0, z1 = g['z'][0] + oz, g['z'][1] + oz
        fh.write("  %s:\n    world: %s\n    min: [%d, %d, %d]\n    max: [%d, %d, %d]\n    blocks:\n" % (gid, WORLD, x0, y0, z0, x1, y1, z1))
        for x, y, z in sorted(cells, key=lambda c: (c[1], c[2], c[0])):
            fh.write("      - %s\n" % yq("%d %d %d %s" % (x + ox - x0, y + oy - y0, z + oz - z0, state)))

    fh.write("\n# world x y z yaw pitch\n")
    fh.write("#   anchor  where players arrive       room1, room2  where mobs rise out of the ground\n")
    fh.write("#   altar   where the mini-boss rises   guards        the throne room guards (the sunken gold blocks and more)\n")
    fh.write("#   boss    where the final boss rises (first) and moves to (the rest)\n")
    fh.write("#   chests_room2  the same for the south hall\n")
    fh.write("#   chests  where an artifact can hide. A chest is placed at any spot that is not a container yet\npoints:\n")
    for group, spots in points.items():
        fh.write("  %s:\n" % group)
        for sp in spots:
            fh.write("    - %s\n" % yq(sp))

summary = {k: len(v) for k, v in points.items()}
print("gates:", {g: len(c[0]) for g, c in gate_cells.items()})
print("regions:", {k: (v[0], len(v[1])) for k, v in regions.items()})
print("points:", summary)
print("origin:", ox, oy, oz, "size", W, H, L)
