#!/usr/bin/env python3
"""Compile the path-only SVG artwork into Android vectors (Python stdlib only)."""
from pathlib import Path
import argparse
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SVG = '{http://www.w3.org/2000/svg}'
ANDROID = 'http://schemas.android.com/apk/res/android'
AAPT = 'http://schemas.android.com/aapt'
ET.register_namespace('android', ANDROID)
ET.register_namespace('aapt', AAPT)

PATH_ATTRS = {'d', 'fill', 'fill-opacity', 'fill-rule'}
STROKE_ATTRS = {'stroke', 'stroke-width', 'stroke-linecap', 'stroke-linejoin'}
# The notification bitmap maps this square of the 108-unit artwork onto the icon.
NOTIFY_WINDOW_CX, NOTIFY_WINDOW_CY, NOTIFY_WINDOW = 54.0, 55.5, 69.0


def attrs(**values):
    return {f'{{{ANDROID}}}{key}': str(value) for key, value in values.items()}


def vector(paths, gradients, *, legacy=False):
    result = ET.Element('vector', attrs(width='48dp' if legacy else '108dp',
        height='48dp' if legacy else '108dp', viewportWidth=72 if legacy else 108,
        viewportHeight=72 if legacy else 108))
    parent = result
    if legacy:
        parent = ET.SubElement(result, 'group', attrs(translateX=-18, translateY=-18))
        ET.SubElement(parent, 'clip-path', attrs(pathData=
            'M40 18H68Q90 18 90 40V68Q90 90 68 90H40Q18 90 18 68V40Q18 18 40 18Z'))
    for source in paths:
        if source.tag != SVG + 'path':
            raise ValueError('Artwork layers must contain only paths')
        unsupported = set(source.attrib) - PATH_ATTRS - STROKE_ATTRS
        if unsupported:
            raise ValueError(f'Unsupported SVG attributes: {unsupported}')
        fill = source.get('fill', '#000000')
        stroke = source.get('stroke')
        if fill == 'none' and stroke is None:
            raise ValueError('A path needs a fill or a stroke')
        path = ET.SubElement(parent, 'path', attrs(pathData=source.attrib['d']))
        if fill == 'none':
            path.set(f'{{{ANDROID}}}strokeColor', stroke)
            path.set(f'{{{ANDROID}}}strokeWidth', source.attrib['stroke-width'])
            if source.get('stroke-linecap'):
                path.set(f'{{{ANDROID}}}strokeLineCap', source.attrib['stroke-linecap'])
            if source.get('stroke-linejoin'):
                path.set(f'{{{ANDROID}}}strokeLineJoin', source.attrib['stroke-linejoin'])
            continue
        path.set(f'{{{ANDROID}}}fillAlpha', source.get('fill-opacity', '1'))
        if source.get('fill-rule') == 'evenodd':
            path.set(f'{{{ANDROID}}}fillType', 'evenOdd')
        if not fill.startswith('url(#'):
            path.set(f'{{{ANDROID}}}fillColor', fill)
            continue
        gradient = gradients[fill[5:-1]]
        resource = ET.SubElement(path, f'{{{AAPT}}}attr', {'name': 'android:fillColor'})
        compiled = ET.SubElement(resource, 'gradient', attrs(type='linear',
            startX=gradient.attrib['x1'], startY=gradient.attrib['y1'],
            endX=gradient.attrib['x2'], endY=gradient.attrib['y2']))
        for stop in gradient:
            ET.SubElement(compiled, 'item', attrs(offset=stop.attrib['offset'],
                color=stop.attrib['stop-color']))
    return result


def generate():
    svg = ET.parse(ROOT / 'artwork/icon.svg').getroot()
    gradients = {g.attrib['id']: g for g in svg.iter(SVG + 'linearGradient')}
    layers = {g.attrib['id']: list(g) for g in svg.iter(SVG + 'g') if 'id' in g.attrib}
    mono = list(ET.parse(ROOT / 'artwork/icon-monochrome.svg').getroot())
    outputs = {
        'drawable/ic_launcher_background.xml': vector(layers['background'], gradients),
        'drawable/ic_launcher_foreground.xml': vector(layers['foreground'], gradients),
        'drawable/ic_launcher_monochrome.xml': vector(mono, {}),
        'mipmap-anydpi/ic_launcher.xml': vector(layers['background'] + layers['foreground'], gradients, legacy=True),
    }
    for api in (26, 33):
        adaptive = ET.Element('adaptive-icon')
        for layer in ('background', 'foreground') + (('monochrome',) if api == 33 else ()):
            ET.SubElement(adaptive, layer, attrs(drawable=f'@drawable/ic_launcher_{layer}'))
        outputs[f'mipmap-anydpi-v{api}/ic_launcher.xml'] = adaptive
    return outputs


def path_data(d):
    """Translate SVG path data into Canvas calls covering the commands the artwork uses."""
    lines = []
    methods = {'M': ('moveTo', 2), 'L': ('lineTo', 2), 'Q': ('quadTo', 4), 'C': ('cubicTo', 6)}
    x = y = 0.0
    for command, values in command_stream(d):
        if command == 'Z':
            lines.append('        path.close();')
            continue
        if command in ('H', 'V'):
            number = float(values[0])
            if command == 'H':
                x = number
            else:
                y = number
            method, points = 'lineTo', [x, y]
        else:
            method, count = methods[command]
            points = [float(n) for n in values[:count]]
            x, y = points[-2:]
        lines.append('        path.' + method + '(' + ', '.join(f'{n:g}f' for n in points) + ');')
    return lines


def command_stream(d):
    """Walk the path data, yielding (command, arguments). Implicit repeats after M are L."""
    tokens = re.findall(r'[A-Za-z]|-?\d+(?:\.\d+)?', d)
    counts = {'M': 2, 'L': 2, 'H': 1, 'V': 1, 'Q': 4, 'C': 6}
    index = 0
    command = None
    while index < len(tokens):
        if tokens[index].isalpha():
            command = tokens[index]
            index += 1
            if command == 'Z':
                yield command, []
                continue
        elif command in (None, 'M'):
            if command is None:
                raise ValueError(f'Path data starts with a number: {d}')
            command = 'L'
        if command not in counts:
            raise ValueError(f'Unsupported path command: {command}')
        count = counts[command]
        yield command, tokens[index:index + count]
        index += count


def notification_source():
    """Use the same monochrome mark in QQ's notification, without cross-package resources."""
    lines = []
    for index, path in enumerate(ET.parse(ROOT / 'artwork/icon-monochrome.svg').getroot()):
        fill = path.get('fill', '#000000')
        stroke = path.get('stroke')
        if fill == 'none' and stroke is None:
            raise ValueError('A path needs a fill or a stroke')
        lines.append('        path.reset();')
        if path.get('fill-rule') == 'evenodd':
            lines.append('        path.setFillType(Path.FillType.EVEN_ODD);')
        lines.extend(path_data(path.attrib['d']))
        if fill == 'none':
            lines.append('        paint.setStyle(Paint.Style.STROKE);')
            lines.append('        paint.setStrokeWidth(%sf);' % path.attrib['stroke-width'])
        else:
            lines.append('        paint.setStyle(Paint.Style.FILL);')
        lines.append('        canvas.drawPath(path, paint);')
    left = NOTIFY_WINDOW_CX - NOTIFY_WINDOW / 2
    top = NOTIFY_WINDOW_CY - NOTIFY_WINDOW / 2
    return '\n'.join([
        '// Generated by scripts/generate-icons.py from artwork/icon-monochrome.svg.',
        'package com.satori.qq.core;',
        '',
        'import android.graphics.Bitmap;',
        'import android.graphics.Canvas;',
        'import android.graphics.Color;',
        'import android.graphics.Paint;',
        'import android.graphics.Path;',
        'import android.graphics.drawable.Icon;',
        '',
        '/** Cached alpha icon: safe to use from the QQ host context on API 26+. */',
        'final class StatusIcon {',
        '    private static Icon cached;',
        '    static synchronized Icon get() {',
        '        if (cached != null) return cached;',
        '        Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);',
        '        Canvas canvas = new Canvas(bitmap);',
        f'        canvas.scale(96f / {NOTIFY_WINDOW:g}f, 96f / {NOTIFY_WINDOW:g}f);',
        f'        canvas.translate({-left:g}f, {-top:g}f);',
        '        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);',
        '        paint.setColor(Color.WHITE);',
        '        paint.setStrokeCap(Paint.Cap.ROUND);',
        '        paint.setStrokeJoin(Paint.Join.ROUND);',
        '        Path path = new Path();',
        *lines,
        '        cached = Icon.createWithBitmap(bitmap);',
        '        return cached;',
        '    }',
        '    private StatusIcon() {}',
        '}',
        '',
    ])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='fail if committed vectors differ from SVG')
    args = parser.parse_args()
    for name, element in generate().items():
        ET.indent(element, space='    ')
        text = '<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by scripts/generate-icons.py; edit artwork/*.svg. -->\n' + ET.tostring(element, encoding='unicode') + '\n'
        path = ROOT / 'res' / name
        if args.check:
            if not path.exists() or path.read_text() != text:
                raise SystemExit(f'Out of date: {path}')
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
    icon = ROOT / 'src/com/satori/qq/core/StatusIcon.java'
    source = notification_source()
    if args.check:
        if not icon.exists() or icon.read_text() != source:
            raise SystemExit(f'Out of date: {icon}')
    else:
        icon.write_text(source)
    print('SVG / Android vectors are in sync' if args.check else 'Generated Android icon resources')


if __name__ == '__main__':
    main()
