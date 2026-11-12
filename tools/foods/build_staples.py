#!/usr/bin/env python3
"""Builds the bundled common-foods table for the journal's food search.

The numbers are not typed by hand. staples_sources.psv names a USDA FoodData Central record
(SR Legacy, public domain) for every food and says which of its household measures become
portions; staples_names.psv holds the food's name in each app language. This script reads the
USDA download and writes Common/src/mobile/assets/foods/staples.tsv.

Carbohydrate is written the way EU and UK labels count it: USDA "carbohydrate, by difference"
minus dietary fibre. A record with no fibre figure is refused, so nothing is overstated.

    curl -LO https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip
    unzip FoodData_Central_sr_legacy_food_csv_2018-04.zip
    python3 tools/foods/build_staples.py FoodData_Central_sr_legacy_food_csv_2018-04
"""
import csv
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', '..', 'Common', 'src', 'mobile', 'assets', 'foods', 'staples.tsv')
NUTRIENTS = {'1003': 'protein', '1004': 'fat', '1005': 'carbs', '1008': 'kcal', '1079': 'fiber'}
PORTION_KINDS = ('piece', 'slice', 'cup', 'glass', 'tbsp', 'tsp', 'serving')
# The order the names are written in staples_names.psv.
LANGUAGES = ('en', 'ru', 'uk', 'be', 'pl', 'de', 'nl', 'sv', 'fr', 'it', 'pt', 'hu', 'tr', 'zh', 'mn', 'so')


def rows(path):
    with open(path, encoding='utf-8') as handle:
        for line in handle:
            line = line.rstrip('\n')
            if line and not line.startswith('#'):
                yield line.split('|')


def load_usda(directory, wanted):
    foods = {fdc_id: {'nutrients': {}, 'portions': []} for fdc_id in wanted}
    with open(os.path.join(directory, 'food.csv'), encoding='utf-8') as handle:
        for row in csv.DictReader(handle):
            if row['fdc_id'] in foods:
                foods[row['fdc_id']]['name'] = row['description']
    with open(os.path.join(directory, 'food_nutrient.csv'), encoding='utf-8') as handle:
        for row in csv.DictReader(handle):
            name = NUTRIENTS.get(row['nutrient_id'])
            if name and row['fdc_id'] in foods:
                foods[row['fdc_id']]['nutrients'][name] = float(row['amount'])
    with open(os.path.join(directory, 'food_portion.csv'), encoding='utf-8') as handle:
        for row in csv.DictReader(handle):
            if row['fdc_id'] in foods:
                foods[row['fdc_id']]['portions'].append(
                    (float(row['amount'] or 1), row['modifier'], float(row['gram_weight'])))
    return foods


def resolve_portions(key, food, spec):
    resolved = []
    for match in re.finditer(r'(\w+)=((?:@[\d.]+)|(?:[^=]+?))(?=\s+\w+=|$)', spec.strip()):
        kind, measure = match.group(1), match.group(2).strip()
        if kind not in PORTION_KINDS:
            sys.exit('%s: unknown portion kind %s' % (key, kind))
        if measure.startswith('@'):
            resolved.append((kind, float(measure[1:])))
            continue
        factor = 1.0
        if '*' in measure:
            measure, factor_text = measure.split('*')
            measure, factor = measure.strip(), float(factor_text)
        candidates = [p for p in food['portions'] if measure.lower() in p[1].lower()]
        if not candidates:
            sys.exit('%s: no USDA measure matching "%s"' % (key, measure))
        candidates.sort(key=lambda p: (p[1].lower() != measure.lower(), p[0] != 1, len(p[1])))
        amount, _, grams = candidates[0]
        resolved.append((kind, grams / amount * factor))
    return resolved


def number(value):
    text = '%.1f' % value
    return text[:-2] if text.endswith('.0') else text


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    sources = list(rows(os.path.join(HERE, 'staples_sources.psv')))
    names = {row[0]: row[1:] for row in rows(os.path.join(HERE, 'staples_names.psv'))}
    usda = load_usda(sys.argv[1], {row[1] for row in sources})
    lines = ['\t'.join(('key', 'carbs', 'protein', 'fat', 'fiber', 'kcal', 'portions') + LANGUAGES)]
    for key, fdc_id, description, spec in sources:
        food = usda[fdc_id]
        if food.get('name') != description:
            sys.exit('%s: USDA record %s is "%s", not "%s"' % (key, fdc_id, food.get('name'), description))
        nutrients = food['nutrients']
        missing = [n for n in ('carbs', 'protein', 'fat', 'fiber', 'kcal') if n not in nutrients]
        if missing:
            sys.exit('%s: USDA record %s has no %s' % (key, fdc_id, ', '.join(missing)))
        if key not in names or len(names[key]) != len(LANGUAGES):
            sys.exit('%s: needs a name in each of %d languages' % (key, len(LANGUAGES)))
        if any('\t' in name or not name.strip() for name in names[key]):
            sys.exit('%s: empty name or a tab in a name' % key)
        portions = ','.join('%s:%s' % (kind, number(grams)) for kind, grams in resolve_portions(key, food, spec))
        lines.append('\t'.join([
            key,
            number(max(0.0, nutrients['carbs'] - nutrients['fiber'])),
            number(nutrients['protein']),
            number(nutrients['fat']),
            number(nutrients['fiber']),
            '%d' % round(nutrients['kcal']),
            portions,
        ] + names[key]))
    unused = set(names) - {row[0] for row in sources}
    if unused:
        sys.exit('names without a source: %s' % ', '.join(sorted(unused)))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8') as handle:
        handle.write('\n'.join(lines) + '\n')
    print('%d foods -> %s' % (len(lines) - 1, os.path.normpath(OUT)))


if __name__ == '__main__':
    main()
