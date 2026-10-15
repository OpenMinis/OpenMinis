#!/usr/bin/env python3
"""Add plugin_market_subtitle to the locales that never had it."""
import os

RES = "src/android/app/src/main/res"
T = {
    "values-de": "Dev-Suites, lokale Tools und Online-OpenAPI-Tools",
    "values-es": "Suites de desarrollo, herramientas locales y OpenAPI en línea",
    "values-fr": "Suites de dev, outils locaux et OpenAPI en ligne",
    "values-fil": "Mga dev suite, lokal na tool, at mga online OpenAPI tool",
    "values-in": "Suit dev, alat lokal, dan alat OpenAPI daring",
    "values-ja": "開発スイート、ローカルツール、オンライン OpenAPI ツール",
    "values-ko": "개발 스위트, 로컬 도구 및 온라인 OpenAPI 도구",
    "values-ms": "Suit dev, alat tempatan dan alat OpenAPI dalam talian",
    "values-pl": "Pakiety dev, narzędzia lokalne i online OpenAPI",
    "values-pt-rBR": "Suítes de dev, ferramentas locais e OpenAPI on-line",
    "values-ro": "Sușite dev, instrumente locale și OpenAPI online",
    "values-ru": "Наборы для разработки, локальные и онлайн OpenAPI-инструменты",
    "values-th": "ชุดพัฒนา เครื่องมือภายใน และเครื่องมือ OpenAPI ออนไลน์",
    "values-tr": "Geliştirme paketleri, yerel araçlar ve çevrimiçi OpenAPI araçları",
}

for locale, value in T.items():
    path = f"{RES}/{locale}/strings.xml"
    src = open(path, encoding="utf-8").read()
    if 'name="plugin_market_subtitle"' in src:
        print(locale, "already has it")
        continue
    anchor = '    <string name="plugin_market_title">'
    assert anchor in src, path
    line = f'    <string name="plugin_market_subtitle">{value}</string>\n'
    src = src.replace(anchor, line + anchor, 1)
    open(path, "w", encoding="utf-8").write(src)
    print(locale, "added")
print("done")
