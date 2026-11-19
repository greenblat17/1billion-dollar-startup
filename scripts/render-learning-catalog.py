#!/usr/bin/env python3
"""Render the editorial review from the same JSON that the backend imports."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / 'learning-catalog/v1/catalog.json'
OUTPUT = ROOT / 'ai_docs/product/2026-11-15-learning-catalog-review.md'


def render(data):
    def cell(value):
        return str(value).replace('|', '\\|').replace('\n', ' ')

    topics = data['topics']
    levels = ('A1', 'A2', 'B1', 'B2', 'C1')
    lines = [
        '# SP-02 v5.5: учебные каталоги — содержательная приёмка', '',
        '> Сгенерировано из `learning-catalog/v1/catalog.json`. Править исходный JSON и повторять `python3 scripts/render-learning-catalog.py`.', '',
        f'Версия: `{data["version"]}`. **Содержание ожидает приёмки пользователя. В runtime не опубликовано.**', '',
        f'Подготовлены {len(data["skills"])} навыков, {len(topics)} Topics, {len(data["problems"])} типов проблем.',
        'Число записей описывает этот проект, а не согласованную норму полноты. Каталог не является сертифицированной программой CEFR.', '',
        '## Что подтверждено и что требует проверки', '',
        '- Подтверждено продуктом: шесть навыков, четыре в MVP, девять типов проблем, отдельные уровневые Topics, общий каталог без ученических состояний.',
        '- Подготовлено агентом: весь набор тем A1–C1, формулировки способностей, конкретное распределение уровней.',
        '- Ожидает вашей приёмки: полнота и границы тем, уровни каждой записи, ожидаемые способности.',
        '- Не проверено на учениках: диагностическая различимость тем, достаточность покрытия реальных целей, качество переноса между голосами и ситуациями.', '',
        '## Обзор покрытия', '',
        '| Навык | A1 | A2 | B1 | B2 | C1 |', '| --- | ---: | ---: | ---: | ---: | ---: |',
    ]
    for skill in data['skills']:
        counts = [str(sum(t['skillId'] == skill['id'] and t['cefr'] == level for t in topics)) for level in levels]
        lines.append('| ' + ' | '.join([skill['title']] + (counts if skill['mvp'] else ['Вне MVP'] * 5)) + ' |')
    lines += ['', 'Области покрытия: Grammar — простое предложение, время/аспект, вопросы, модальность, условность, залог, связь частей и смысловой фокус; Speaking — контакт, обмен, рассказ, объяснение, аргументация, взаимодействие и восстановление понимания; Listening — детали, диалоги, инструкции, рассказы, объяснения, медиа, неявный смысл и разнообразие речи; Vocabulary — бытовые и рабочие области, сочетаемость, переносные значения, точность и регистр.', '',
              '## Вопросы содержательной приёмки', '',
              '1. Источники Grammar часто объединяют A1–A2 и B1–B2. Разделение внутри диапазонов — решение Speaky, а не точная разметка источника.',
              '2. Present Perfect introduction оставлен на A2 по каркасу v5.5; contrast на B1. Past Perfect оставлен на B2. Эти границы требуют отдельной проверки.',
              '3. Topic Map содержит только целевые способности. Зависимости и последовательность обучения относятся к будущей Roadmap Generation.',
              '4. Каталог не содержит prerequisites, обязательного Curriculum Order или условий допуска к Conversation.',
              '5. Vocabulary описывает общие области и способности. C1 goal-specific terminology — способность работать с нужной терминологией, а не готовый отраслевой словарь.',
              '6. Listening требует разнообразных источников речи. Понимание одного голоса репетитора не доказывает весь Topic.',
              '7. Fluency включена в самостоятельное поддержание речи; численных норм скорости и пауз нет. Pronunciation, Reading и Writing не расширяют обязательный объём MVP.',
              '8. Повторяющиеся области на разных уровнях различаются объёмом способности. Проверить, что границы позволяют отличить уровни, а не просто повторяют названия.', '',
              '## Skill Map', '', '| ID | Skill | Содержание | MVP |', '| --- | --- | --- | --- |']
    for s in data['skills']:
        lines.append('| ' + ' | '.join(map(cell, [s['id'], s['title'], s['description'], 'Да' if s['mvp'] else 'Нет'])) + ' |')
    lines += ['', '## Problem Map', '', '| ID | Skill | Тип | Содержание |', '| --- | --- | --- | --- |']
    for p in data['problems']:
        lines.append('| ' + ' | '.join(map(cell, [p['id'], p['skillId'], p['title'], p['description']])) + ' |')
    lines += ['', 'Типы проблем применимы к Topics соответствующего Skill. Наличие типа в каталоге не является диагнозом ученика.', '', '## Источники и границы их использования', '']
    for source in data['sources']:
        url = '../../' + source['url'][5:] if source['url'].startswith('repo:') else source['url']
        lines += [f'- **{source["id"]}** — [{source["title"]}]({url}), {source["section"]}. {source["usage"]}']
    lines += ['', 'Формулировки Topics и rationale написаны для Speaky. Тексты упражнений, дескрипторы, аудио и словники внешних источников не импортировались. Ссылки сверены 2026-11-15; указатель раздела не означает независимую проверку каждого Topic.', '']
    for level in levels:
        lines += [f'## {level}', '']
        for skill in data['skills']:
            if not skill['mvp']:
                continue
            lines += [f'### {skill["title"]}', '', '| ID / Topic | Ожидаемая способность | Объём и границы | Источники |', '| --- | --- | --- | --- |']
            for t in topics:
                if t['skillId'] == skill['id'] and t['cefr'] == level:
                    lines.append('| ' + ' | '.join(map(cell, [f'{t["id"]}: {t["title"]}', t['objective'], t['scope'], ', '.join(t['sourceIds'])])) + ' |')
            lines.append('')
    return '\n'.join(lines).rstrip() + '\n'

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    result = render(json.loads(MANIFEST.read_text()))
    if args.check:
        if not OUTPUT.exists() or OUTPUT.read_text() != result:
            raise SystemExit('Review document is stale: run scripts/render-learning-catalog.py')
        print('Catalog review matches source')
    else:
        OUTPUT.write_text(result)
        print(OUTPUT.relative_to(ROOT))


if __name__ == '__main__':
    main()
