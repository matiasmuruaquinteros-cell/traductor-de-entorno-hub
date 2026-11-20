#!/usr/bin/env python3
"""
Placa de una cara (pertinax) para los lentes: ESP32-S3-CAM + MAX98357A + tira de salida.
Genera:
  - lentes_placa.sch / lentes_placa.brd  (formato EAGLE 9, se abren en Fusion 360)
  - lentes_placa_cobre_1a1.pdf           (para planchar el toner, escala 1:1, SIN espejar)
  - lentes_placa_armado.pdf              (vista de arriba con nombres, para perforar y armar)
  - vista_previa.png
y verifica separaciones y conectividad.
"""
import math, itertools, sys
from xml.sax.saxutils import escape

P = 2.54
FILA = 25.4                  # distancia entre las dos hileras del ESP32 (centro a centro)
XL, XR = 10.0, 10.0 + FILA   # hileras izquierda / derecha
Y1 = 58.26                   # pin 1 (arriba); pin 20 queda en y = 10
def y(n): return round(Y1 - (n - 1) * P, 3)
JX = round(XR + 10.16, 3)    # columna del amplificador
J3Y = round(Y1 + 12.7, 3)    # tira de salida (arriba, fuera del voladizo de la antena)
def j3x(k): return round(4.92 + (k - 1) * P, 3)
BUS = round(J3Y + 3.81, 3)   # bus de GND por arriba de la tira
YA, YB = round(Y1 + 2.54, 3), round(J3Y - 3.81, 3)
YBOT = 6.19

DRILL, PAD = 1.0, 2.0
W_SIG, W_POW = 0.8, 1.2
CONTORNO = (1.5, 3.0, 54.5, 77.5)

# ---------------- Piezas ----------------
ESP_IZQ = ["3V3", "RST", "IO4", "IO5", "IO6", "IO7", "IO15", "IO16", "IO17", "IO18",
           "IO8", "IO3", "IO46", "IO9", "IO10", "IO11", "IO12", "IO13", "IO14", "5V"]
ESP_DER = ["IO43", "IO44", "IO1", "IO2", "IO42", "IO41", "IO40", "IO39", "IO38", "IO37",
           "IO36", "IO35", "IO0", "IO45", "IO48", "IO47", "IO21", "IO20", "IO19", "GND"]
AMP = ["LRC", "BCLK", "DIN", "GAIN", "SD", "GND", "VIN"]
SALIDA = ["5V", "GND", "3V3_MIC", "BOT_VOZ", "GND_VOZ", "BOT_FOTO", "GND_FOTO",
          "MIC_SD", "MIC_WS", "MIC_SCK", "GND_MIC", "MIC_LR"]

# nombre -> (paquete, posicion del elemento, pads {num: (x,y)}, nombres de pin)
piezas = {}
def tira_vertical(nombre, x, n, pin1_y, nombres, valor):
    pads = {i: (x, round(pin1_y - (i - 1) * P, 3)) for i in range(1, n + 1)}
    piezas[nombre] = dict(paquete=f"1X{n:02d}V", origen=(x, pads[n][1]), pads=pads,
                          nombres=nombres, valor=valor, n=n, vertical=True)
def tira_horizontal(nombre, x0, yy, n, nombres, valor):
    pads = {i: (round(x0 + (i - 1) * P, 3), yy) for i in range(1, n + 1)}
    piezas[nombre] = dict(paquete=f"1X{n:02d}H", origen=(x0, yy), pads=pads,
                          nombres=nombres, valor=valor, n=n, vertical=False)

tira_vertical("J1", XL, 20, Y1, ESP_IZQ, "ESP32_IZQ")
tira_vertical("J2", XR, 20, Y1, ESP_DER, "ESP32_DER")
tira_vertical("JP1", JX, 7, y(8), AMP, "MAX98357A")
tira_horizontal("J3", j3x(1), J3Y, 12, SALIDA, "SALIDA")

def pad(ref, n): return piezas[ref]["pads"][n]

# ---------------- Redes: contactos + pistas ----------------
redes = {
    "5V":      dict(c=[("J3", 1), ("J1", 20), ("JP1", 7)], w=W_POW, p=[
        [pad("J3", 1), (4.92, YBOT), (JX + 3.81, YBOT), (JX + 3.81, y(14)), pad("JP1", 7)],
        [(XL, YBOT), pad("J1", 20)]]),
    "GND":     dict(c=[("J3", 2), ("J3", 5), ("J3", 7), ("J3", 11), ("J3", 12), ("J2", 20), ("JP1", 6)], w=W_POW, p=[
        [pad("J3", 2), (j3x(2), BUS), (JX + 6.35, BUS), (JX + 6.35, y(13)), pad("JP1", 6)],
        [pad("J3", 5), (j3x(5), BUS)], [pad("J3", 7), (j3x(7), BUS)],
        [pad("J3", 11), (j3x(11), BUS)], [pad("J3", 12), (j3x(12), BUS)],
        [pad("J2", 20), (XR + 6.35, y(20)), (XR + 6.35, y(13)), pad("JP1", 6)]]),
    "3V3":     dict(c=[("J3", 3), ("J1", 1)], w=1.0, p=[[pad("J1", 1), pad("J3", 3)]]),
    "BOT_VOZ": dict(c=[("J3", 4), ("J1", 19)], w=W_SIG, p=[
        [pad("J1", 19), (XL + P, y(19)), pad("J3", 4)]]),
    "BOT_FOTO": dict(c=[("J3", 6), ("J2", 16)], w=W_SIG, p=[
        [pad("J2", 16), (XR - 4 * P, y(16)), (XR - 4 * P, YA), (j3x(6), YB), pad("J3", 6)]]),
    "MIC_SD":  dict(c=[("J3", 8), ("J2", 14)], w=W_SIG, p=[
        [pad("J2", 14), (XR - 3 * P, y(14)), (XR - 3 * P, YA), (j3x(8), YB), pad("J3", 8)]]),
    "MIC_WS":  dict(c=[("J3", 9), ("J2", 6)], w=W_SIG, p=[
        [pad("J2", 6), (XR - 2 * P, y(6)), (XR - 2 * P, YA), (j3x(9), YB), pad("J3", 9)]]),
    "MIC_SCK": dict(c=[("J3", 10), ("J2", 5)], w=W_SIG, p=[
        [pad("J2", 5), (XR - P, y(5)), (XR - P, YA), (j3x(10), YB), pad("J3", 10)]]),
    # Con GPIO39 = LRC y GPIO38 = BCLK las pistas no se cruzan (cambio de 2 lineas en el firmware)
    "I2S_LRC":  dict(c=[("JP1", 1), ("J2", 8)], w=W_SIG, p=[[pad("J2", 8), pad("JP1", 1)]]),
    "I2S_BCLK": dict(c=[("JP1", 2), ("J2", 9)], w=W_SIG, p=[[pad("J2", 9), pad("JP1", 2)]]),
    "I2S_DIN":  dict(c=[("JP1", 3), ("J2", 17)], w=W_SIG, p=[
        [pad("J2", 17), (XR + 1.5 * P, y(17)), (XR + 1.5 * P, y(10)), pad("JP1", 3)]]),
}

def r3(v): return round(v, 3)
segmentos = []   # (red, x1,y1,x2,y2, ancho)
for red, d in redes.items():
    for linea in d["p"]:
        for a, b in zip(linea, linea[1:]):
            segmentos.append((red, r3(a[0]), r3(a[1]), r3(b[0]), r3(b[1]), d["w"]))

pad_red = {}
for red, d in redes.items():
    for ref, n in d["c"]:
        pad_red[(ref, n)] = red
todos_pads = [(ref, n, xy) for ref, pz in piezas.items() for n, xy in pz["pads"].items()]

# ---------------- Verificacion ----------------
def dist_pt_seg(px, py, x1, y1, x2, y2):
    dx, dy = x2 - x1, y2 - y1
    L = dx * dx + dy * dy
    t = 0 if L == 0 else max(0, min(1, ((px - x1) * dx + (py - y1) * dy) / L))
    return math.hypot(px - x1 - t * dx, py - y1 - t * dy)
def seg_cruzan(a, b):
    def orient(p, q, r): return (q[0]-p[0])*(r[1]-p[1]) - (q[1]-p[1])*(r[0]-p[0])
    p1, p2, p3, p4 = (a[1], a[2]), (a[3], a[4]), (b[1], b[2]), (b[3], b[4])
    d1, d2, d3, d4 = orient(p3, p4, p1), orient(p3, p4, p2), orient(p1, p2, p3), orient(p1, p2, p4)
    return (d1 * d2 < 0) and (d3 * d4 < 0)
def dist_seg_seg(a, b):
    if seg_cruzan(a, b): return 0.0
    return min(dist_pt_seg(a[1], a[2], *b[1:5]), dist_pt_seg(a[3], a[4], *b[1:5]),
               dist_pt_seg(b[1], b[2], *a[1:5]), dist_pt_seg(b[3], b[4], *a[1:5]))

errores, minimo = [], 99.0
R_PAD = PAD / 2 * 1.15            # margen por el pad cuadrado del pin 1
for a, b in itertools.combinations(segmentos, 2):
    if a[0] == b[0]: continue
    g = dist_seg_seg(a, b) - a[5] / 2 - b[5] / 2
    minimo = min(minimo, g)
    if g < 0.4: errores.append(f"pista {a[0]} muy cerca de {b[0]}: {g:.2f} mm")
for s in segmentos:
    for ref, n, (px, py) in todos_pads:
        if pad_red.get((ref, n)) == s[0]: continue
        g = dist_pt_seg(px, py, *s[1:5]) - s[5] / 2 - R_PAD
        minimo = min(minimo, g)
        if g < 0.4: errores.append(f"pista {s[0]} muy cerca del pad {ref}.{n}: {g:.2f} mm")
    x1, y1_, x2, y2 = s[1:5]
    if not (CONTORNO[0] + 1 < min(x1, x2) and max(x1, x2) < CONTORNO[2] - 1 and
            CONTORNO[1] + 1 < min(y1_, y2) and max(y1_, y2) < CONTORNO[3] - 1):
        errores.append(f"pista {s[0]} demasiado cerca del borde")

# conectividad: cada red tiene que formar un solo grupo que incluya todos sus pads
for red, d in redes.items():
    nodos = {}
    def nodo(pt):
        return nodos.setdefault((round(pt[0], 3), round(pt[1], 3)), (round(pt[0], 3), round(pt[1], 3)))
    padre = {}
    def raiz(k):
        while padre.setdefault(k, k) != k: k = padre[k]
        return k
    def unir(a, b): padre[raiz(a)] = raiz(b)
    segs = [s for s in segmentos if s[0] == red]
    pts = set()
    for s in segs:
        a, b = (s[1], s[2]), (s[3], s[4]); pts |= {a, b}; unir(a, b)
    # puntos que caen sobre otro segmento de la misma red (uniones en T)
    for p_ in list(pts):
        for s in segs:
            if dist_pt_seg(p_[0], p_[1], *s[1:5]) < 1e-6: unir(p_, (s[1], s[2]))
    grupos = {raiz(pad(ref, n)) if pad(ref, n) in pts else ("suelto", ref, n) for ref, n in d["c"]}
    if len(grupos) != 1: errores.append(f"red {red} incompleta: {grupos}")

print(f"Separacion minima entre cobre de redes distintas: {minimo:.2f} mm")
if errores:
    print("\n".join(errores)); sys.exit(1)
print("Verificacion OK: sin cruces, separacion >= 0.4 mm, todas las redes completas")

# ---------------- EAGLE: libreria ----------------
CAPAS = [(1,"Top",4),(16,"Bottom",1),(17,"Pads",2),(18,"Vias",2),(19,"Unrouted",6),(20,"Dimension",15),
         (21,"tPlace",7),(22,"bPlace",7),(23,"tOrigins",15),(24,"bOrigins",15),(25,"tNames",7),(26,"bNames",7),
         (27,"tValues",7),(28,"bValues",7),(29,"tStop",7),(30,"bStop",7),(31,"tCream",7),(32,"bCream",7),
         (39,"tKeepout",4),(40,"bKeepout",1),(41,"tRestrict",4),(42,"bRestrict",1),(43,"vRestrict",2),
         (44,"Drills",7),(45,"Holes",7),(46,"Milling",3),(47,"Measures",7),(48,"Document",7),(49,"Reference",7),
         (51,"tDocu",7),(52,"bDocu",7),(91,"Nets",2),(92,"Busses",1),(93,"Pins",2),(94,"Symbols",4),
         (95,"Names",7),(96,"Values",4),(97,"Info",7),(98,"Guide",6)]
def capas_xml():
    return "<layers>\n" + "".join(
        f'<layer number="{n}" name="{nm}" color="{c}" fill="1" visible="yes" active="yes"/>\n'
        for n, nm, c in CAPAS) + "</layers>\n"

def paquete_xml(pz):
    n, vert = pz["n"], pz["vertical"]
    ox, oy = pz["origen"]
    s = f'<package name="{pz["paquete"]}">\n<description>Tira de {n} pines, paso 2,54 mm</description>\n'
    for i, (x, yy) in pz["pads"].items():
        lx, ly = r3(x - ox), r3(yy - oy)
        forma = ' shape="square"' if i == 1 else ""
        s += f'<pad name="{i}" x="{lx}" y="{ly}" drill="{DRILL}" diameter="{PAD}"{forma}/>\n'
    xs = [r3(x - ox) for x, _ in pz["pads"].values()]; ys = [r3(yy - oy) for _, yy in pz["pads"].values()]
    x0, x1, y0, y1_ = min(xs) - 1.27, max(xs) + 1.27, min(ys) - 1.27, max(ys) + 1.27
    for a, b, c, d in [(x0, y0, x1, y0), (x1, y0, x1, y1_), (x1, y1_, x0, y1_), (x0, y1_, x0, y0)]:
        s += f'<wire x1="{r3(a)}" y1="{r3(b)}" x2="{r3(c)}" y2="{r3(d)}" width="0.2" layer="21"/>\n'
    if vert:
        s += f'<text x="{r3(x0)}" y="{r3(y1_ + 0.6)}" size="1.27" layer="25">&gt;NAME</text>\n'
    else:
        s += f'<text x="{r3(x0)}" y="{r3(y0 - 1.9)}" size="1.27" layer="25">&gt;NAME</text>\n'
    return s + "</package>\n"

def simbolo_xml(nombre, nombres):
    n = len(nombres)
    alto = n * P
    s = f'<symbol name="{nombre}">\n'
    s += f'<wire x1="-2.54" y1="{P}" x2="7.62" y2="{P}" width="0.254" layer="94"/>\n'
    s += f'<wire x1="7.62" y1="{P}" x2="7.62" y2="{r3(-alto)}" width="0.254" layer="94"/>\n'
    s += f'<wire x1="7.62" y1="{r3(-alto)}" x2="-2.54" y2="{r3(-alto)}" width="0.254" layer="94"/>\n'
    s += f'<wire x1="-2.54" y1="{r3(-alto)}" x2="-2.54" y2="{P}" width="0.254" layer="94"/>\n'
    for i, pn in enumerate(nombres):
        s += f'<pin name="{escape(pn)}" x="-7.62" y="{r3(-i * P)}" length="middle" direction="pas"/>\n'
    s += f'<text x="-2.54" y="{r3(P + 1.27)}" size="1.778" layer="95">&gt;NAME</text>\n'
    s += f'<text x="-2.54" y="{r3(-alto - 2.54)}" size="1.778" layer="96">&gt;VALUE</text>\n'
    return s + "</symbol>\n"

def nombres_unicos(nombres):
    vistos, out = {}, []
    for nm in nombres:
        vistos[nm] = vistos.get(nm, 0) + 1
        out.append(nm if nombres.count(nm) == 1 else f"{nm}@{vistos[nm]}")
    return out
for pz in piezas.values():
    pz["pines"] = nombres_unicos(pz["nombres"])

def libreria_xml(con_simbolos):
    s = '<library name="lentes">\n<description>Piezas de la placa de los lentes</description>\n<packages>\n'
    s += "".join(paquete_xml(pz) for pz in piezas.values()) + "</packages>\n"
    if con_simbolos:
        s += "<symbols>\n" + "".join(simbolo_xml("S_" + ref, pz["pines"]) for ref, pz in piezas.items()) + "</symbols>\n"
        s += "<devicesets>\n"
        for ref, pz in piezas.items():
            s += (f'<deviceset name="D_{ref}" prefix="J" uservalue="yes">\n<gates>\n'
                  f'<gate name="G$1" symbol="S_{ref}" x="0" y="0"/>\n</gates>\n<devices>\n'
                  f'<device name="" package="{pz["paquete"]}">\n<connects>\n')
            for i, pn in enumerate(pz["pines"], start=1):
                s += f'<connect gate="G$1" pin="{escape(pn)}" pad="{i}"/>\n'
            s += '</connects>\n<technologies>\n<technology name=""/>\n</technologies>\n</device>\n</devices>\n</deviceset>\n'
        s += "</devicesets>\n"
    return s + "</library>\n"

CAB = ('<?xml version="1.0" encoding="utf-8"?>\n<!DOCTYPE eagle SYSTEM "eagle.dtd">\n<eagle version="9.6.2">\n<drawing>\n'
       '<settings>\n<setting alwaysvectorfont="no"/>\n<setting verticaltext="up"/>\n</settings>\n'
       '<grid distance="2.54" unitdist="mm" unit="mm" style="lines" multiple="1" display="yes" '
       'altdistance="0.635" altunitdist="mm" altunit="mm"/>\n')
PIE = "</drawing>\n</eagle>\n"

# ---------------- Esquematico ----------------
pos_sch = {"J1": (20.32, 101.6), "J2": (81.28, 101.6), "JP1": (142.24, 101.6), "J3": (190.5, 101.6)}
sch = CAB + capas_xml() + "<schematic>\n<libraries>\n" + libreria_xml(True) + "</libraries>\n"
sch += '<attributes/>\n<variantdefs/>\n<classes>\n<class number="0" name="default" width="0" drill="0"/>\n</classes>\n<parts>\n'
for ref, pz in piezas.items():
    sch += f'<part name="{ref}" library="lentes" deviceset="D_{ref}" device="" value="{pz["valor"]}"/>\n'
sch += "</parts>\n<sheets>\n<sheet>\n<plain>\n"
sch += ('<text x="20.32" y="134.62" size="2.54" layer="97">Lentes inteligentes - placa ESP32-S3-CAM + MAX98357A. '
        'Las redes se unen por nombre (etiquetas).</text>\n')
sch += "</plain>\n<instances>\n"
for ref in piezas:
    x, yy = pos_sch[ref]
    sch += f'<instance part="{ref}" gate="G$1" x="{x}" y="{yy}"/>\n'
sch += "</instances>\n<busses/>\n<nets>\n"
for red, d in redes.items():
    sch += f'<net name="{red}" class="0">\n'
    for ref, n in d["c"]:
        x, yy = pos_sch[ref]
        px, py = r3(x - 7.62), r3(yy - (n - 1) * P)
        pin = piezas[ref]["pines"][n - 1]
        sch += (f'<segment>\n<pinref part="{ref}" gate="G$1" pin="{escape(pin)}"/>\n'
                f'<wire x1="{px}" y1="{py}" x2="{r3(px - 7.62)}" y2="{py}" width="0.1524" layer="91"/>\n'
                f'<label x="{r3(px - 7.62)}" y="{py}" size="1.27" layer="95" rot="R180" xref="no"/>\n</segment>\n')
    sch += "</net>\n"
sch += "</nets>\n</sheet>\n</sheets>\n</schematic>\n" + PIE

# ---------------- Placa ----------------
brd = CAB + capas_xml() + "<board>\n<plain>\n"
cx0, cy0, cx1, cy1 = CONTORNO
for a, b, c, d in [(cx0, cy0, cx1, cy0), (cx1, cy0, cx1, cy1), (cx1, cy1, cx0, cy1), (cx0, cy1, cx0, cy0)]:
    brd += f'<wire x1="{a}" y1="{b}" x2="{c}" y2="{d}" width="0" layer="20"/>\n'
for k, nm in enumerate(SALIDA, start=1):
    brd += f'<text x="{r3(j3x(k) + 0.45)}" y="{r3(J3Y - 1.6)}" size="0.9" layer="21" rot="R270">{escape(nm)}</text>\n'
brd += '</plain>\n<libraries>\n' + libreria_xml(True) + '</libraries>\n<attributes/>\n<variantdefs/>\n'
brd += '<classes>\n<class number="0" name="default" width="0" drill="0"/>\n</classes>\n<designrules name="default"/>\n<autorouter/>\n<elements>\n'
for ref, pz in piezas.items():
    ox, oy = pz["origen"]
    brd += f'<element name="{ref}" library="lentes" package="{pz["paquete"]}" value="{pz["valor"]}" x="{ox}" y="{oy}"/>\n'
brd += "</elements>\n<signals>\n"
for red, d in redes.items():
    brd += f'<signal name="{red}">\n'
    for ref, n in d["c"]:
        brd += f'<contactref element="{ref}" pad="{n}"/>\n'
    for s in segmentos:
        if s[0] == red:
            brd += f'<wire x1="{s[1]}" y1="{s[2]}" x2="{s[3]}" y2="{s[4]}" width="{s[5]}" layer="16"/>\n'
    brd += "</signal>\n"
brd += "</signals>\n</board>\n" + PIE

open("lentes_placa.sch", "w").write(sch)
open("lentes_placa.brd", "w").write(brd)

# ---------------- PDF 1:1 ----------------
from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.lib.colors import black, white, Color

def pdf_cobre(ruta):
    c = canvas.Canvas(ruta, pagesize=A4)
    W, H = A4
    ox, oy = 25 * mm, H - 40 * mm - (cy1 - cy0) * mm     # donde cae (cx0, cy0)
    def T(x, yy): return ox + (x - cx0) * mm, oy + (yy - cy0) * mm
    c.setFont("Helvetica-Bold", 11)
    c.drawString(25 * mm, H - 15 * mm, "COBRE (Bottom) - imprimir al 100% / tamaño real, SIN espejar")
    c.setFont("Helvetica", 8.5)
    c.drawString(25 * mm, H - 20 * mm, "Lentes inteligentes - placa ESP32-S3-CAM + MAX98357A. Vista desde el lado de los componentes.")
    c.drawString(25 * mm, H - 24 * mm, "Comprobá con la regla: la barra de abajo tiene que medir exactamente 50 mm.")
    # contorno
    c.setLineWidth(0.25); c.setDash(2, 2)
    a, b = T(cx0, cy0); c.rect(a, b, (cx1 - cx0) * mm, (cy1 - cy0) * mm); c.setDash()
    # pistas
    c.setStrokeColor(black); c.setLineCap(1); c.setLineJoin(1)
    for s in segmentos:
        c.setLineWidth(s[5] * mm)
        a, b = T(s[1], s[2]); d, e = T(s[3], s[4]); c.line(a, b, d, e)
    # pads con el centro marcado para el punzon/mecha
    for ref, n, (x, yy) in todos_pads:
        a, b = T(x, yy); c.setFillColor(black)
        if n == 1: c.rect(a - PAD / 2 * mm, b - PAD / 2 * mm, PAD * mm, PAD * mm, stroke=0, fill=1)
        else: c.circle(a, b, PAD / 2 * mm, stroke=0, fill=1)
        c.setFillColor(white); c.circle(a, b, 0.35 * mm, stroke=0, fill=1)
    c.setFillColor(black)
    # barra de 50 mm
    a, b = 25 * mm, oy - 15 * mm
    c.setLineWidth(0.6); c.line(a, b, a + 50 * mm, b); c.line(a, b - 2 * mm, a, b + 2 * mm); c.line(a + 50 * mm, b - 2 * mm, a + 50 * mm, b + 2 * mm)
    c.setFont("Helvetica", 8); c.drawString(a + 18 * mm, b + 2.5 * mm, "50 mm")
    # copia de prueba para encajar el ESP en papel
    c.drawString(110 * mm, b + 2.5 * mm, f"Placa: {cx1 - cx0:.1f} x {cy1 - cy0:.1f} mm - agujeros {DRILL} mm")
    c.showPage(); c.save()

def pdf_armado(ruta):
    c = canvas.Canvas(ruta, pagesize=A4)
    W, H = A4
    esc = 2.2   # dibujo ampliado para leer (NO es 1:1)
    ox, oy = 22 * mm, 40 * mm
    def T(x, yy): return ox + (x - cx0) * mm * esc, oy + (yy - cy0) * mm * esc
    c.setFont("Helvetica-Bold", 12)
    c.drawString(22 * mm, H - 14 * mm, "Guía de armado - vista de ARRIBA (lado de los componentes), ampliada x2,2")
    c.setFont("Helvetica", 8.5)
    c.drawString(22 * mm, H - 19 * mm, "Las pistas (gris) van del lado de abajo. Tiras hembra para el ESP32 y el amplificador; tira macho arriba (J3).")
    gris = Color(0.75, 0.75, 0.75); azul = Color(0.2, 0.4, 0.85); rojo = Color(0.85, 0.2, 0.2)
    a, b = T(cx0, cy0); c.setLineWidth(1); c.rect(a, b, (cx1 - cx0) * mm * esc, (cy1 - cy0) * mm * esc)
    # silueta del ESP32 y del modulo (referencia)
    c.setStrokeColor(azul); c.setDash(3, 2)
    a, b = T(XL - 1.6, y(20) - 9); c.rect(a, b, (FILA + 3.2) * mm * esc, (y(1) + 7.2 - y(20) + 9) * mm * esc)
    c.setFont("Helvetica", 7); c.setFillColor(azul)
    a, b = T(XL + 6, y(1) + 4.5); c.drawString(a, b, "antena ESP32 (arriba)")
    a, b = T(XL + 7, y(20) - 7.5); c.drawString(a, b, "USB-C (abajo)")
    c.setStrokeColor(rojo); c.setFillColor(rojo)
    a, b = T(JX - 1.6, y(14) - 2); c.rect(a, b, 9 * mm * esc, (y(8) - y(14) + 4) * mm * esc)
    a, b = T(JX + 1.8, y(14) - 4.5); c.drawString(a, b, "MAX98357A")
    c.setDash(); c.setFillColor(black); c.setStrokeColor(black)
    c.setStrokeColor(gris); c.setLineCap(1)
    for s in segmentos:
        c.setLineWidth(s[5] * mm * esc)
        a, b = T(s[1], s[2]); d, e = T(s[3], s[4]); c.line(a, b, d, e)
    c.setStrokeColor(black)
    for ref, n, (x, yy) in todos_pads:
        a, b = T(x, yy); usado = (ref, n) in pad_red
        c.setFillColor(black if usado else Color(0.55, 0.55, 0.55))
        c.circle(a, b, PAD / 2 * mm * esc, stroke=0, fill=1)
        c.setFillColor(white); c.circle(a, b, DRILL / 2 * mm * esc, stroke=0, fill=1)
        pz = piezas[ref]; nm = pz["nombres"][n - 1]
        c.setFillColor(black if usado else Color(0.5, 0.5, 0.5))
        c.setFont("Helvetica-Bold" if usado else "Helvetica", 6)
        if ref == "J1": c.drawRightString(a - 3 * mm, b - 2, nm)
        elif ref in ("J2", "JP1"): c.drawString(a + 3 * mm, b - 2, nm)
        else:
            c.saveState(); c.translate(a + 1.5, b + 3.2 * mm); c.rotate(90); c.drawString(0, 0, f"{n} {nm}"); c.restoreState()
    c.setFont("Helvetica-Bold", 9)
    for ref, (x, yy) in {"J1": (XL - 4, y(1) + 2.4), "J2": (XR + 0.6, y(1) + 2.4), "JP1": (JX - 1.5, y(8) + 2.2),
                         "J3": (j3x(1) - 3.5, J3Y - 2.5)}.items():
        a, b = T(x, yy); c.setFillColor(black); c.drawString(a, b, ref)
    c.showPage(); c.save()

pdf_cobre("lentes_placa_cobre_1a1.pdf")
pdf_armado("lentes_placa_armado.pdf")

# ---------------- Vista previa ----------------
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt
fig, ax = plt.subplots(figsize=(7, 9.5))
ax.add_patch(plt.Rectangle((cx0, cy0), cx1 - cx0, cy1 - cy0, fill=False, ls="--"))
colores = plt.cm.tab20.colors
for i, red in enumerate(redes):
    for s in segmentos:
        if s[0] == red:
            ax.plot([s[1], s[3]], [s[2], s[4]], lw=s[5] * 4, color=colores[i % 20], solid_capstyle="round")
for ref, n, (x, yy) in todos_pads:
    ax.add_patch(plt.Circle((x, yy), PAD / 2, color="k" if (ref, n) in pad_red else "0.6"))
    ax.add_patch(plt.Circle((x, yy), DRILL / 2, color="w"))
    nm = piezas[ref]["nombres"][n - 1]
    if ref == "J1": ax.text(x - 1.4, yy, nm, ha="right", va="center", fontsize=5)
    elif ref in ("J2", "JP1"): ax.text(x + 1.4, yy, nm, ha="left", va="center", fontsize=5)
    else: ax.text(x, yy + 1.4, nm, ha="center", va="bottom", fontsize=4.5, rotation=90)
ax.set_aspect("equal"); ax.set_xlim(cx0 - 2, cx1 + 2); ax.set_ylim(cy0 - 2, cy1 + 9)
ax.grid(True, lw=0.2); fig.tight_layout(); fig.savefig("vista_previa.png", dpi=170)
print("Archivos generados")
