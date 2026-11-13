"""Opt-in real-provider semantic probe. Synthetic learners, memory store, no Telegram or production writes.
Run: PYTHONPATH=. python evals/tutor_dialogues.py > /tmp/tutor-dialogues.json
Review reply meaning and actual operations together; successful execution is not semantic acceptance.
"""
import asyncio
import json
import sys
import time
from openai import AsyncOpenAI
from app.config import Settings
from app.llm import OpenAiChatModel
from app.tutor import TutorModel, TutorRequest, TutorRuntime
from app.tutor_store import TutorStore, active_booking

SCENARIOS = [
    ['Работаю разработчиком, боюсь ошибиться на встречах. Уровень не знаю. Что ты умеешь?',
     'Сначала объясни, чем confident отличается от confidence.',
     'Запиши на завтра в 19:00, сейчас я в Берлине.',
     'А если я растеряюсь и не смогу говорить?', 'На час позже, пожалуйста.',
     'Может, в пятницу?', 'Не могу завтра.',
     'Отмени пока. Мне английский на самом деле нужен для переезда, а не для работы.',
     'А какая цена и что за roadmap?', 'Давай всё же сейчас.'],
    ['Хочу урок прямо сейчас.', 'Мне вообще не хочется trial.',
     'Можно просто поболтать? Я сегодня впервые выступил перед командой.',
     'Теперь хочу записаться на завтра утром.', 'Я сейчас в Москве, давай точно в 10:00.'],
    ['Запиши на завтра в семь.', 'Я в Лондоне, в семь вечера.',
     'Перенеси на пятницу в то же время. И цель поменяй: теперь путешествия. Как сказать это по-английски?'],
    ['А если бы я захотел урок прямо сейчас?', 'А вообще уроки сейчас доступны?',
     'Хочу урок прямо сейчас.'],
    ['Хочу английский для работы.',
     'Хочу самостоятельно вести встречи с иностранными коллегами.',
     'Понимаю их, но долго подбираю слова и боюсь ошибиться.',
     'Учил в школе, потом год занимался по приложению. Разговорной практики почти не было.',
     'Точный уровень не знаю. Могу рассказать о себе простыми фразами.',
     'Что ты предложишь с учётом этого?'],
    ['Хочу вести встречи на английском. Сейчас понимаю коллег, но боюсь говорить. '
     'Раньше учил в школе и приложении, разговорной практики мало. Думаю, у меня B1.',
     'Я уже рассказал об опыте и уровне. Что предлагаешь?'],
    ['Хочу английский для работы.', 'Запиши на урок прямо сейчас.'],
    ['Нужен полный курс с абсолютного нуля, алфавит ещё не знаю. '
     'Хочу именно последовательную программу грамматики, разговорная практика пока не нужна.',
     'Ты можешь дать именно такой курс?'],
    ['Хочу легче общаться в поездках, чтобы самостоятельно решать вопросы в отеле. '
     'Боюсь отвечать, хотя понимаю. Учил язык давно в школе.',
     'Уровень не знаю и проверять сейчас не хочу.', 'Что предложишь?'],

]

# Indices include the initial /start. These inspect stored outcomes, not reply wording.
# Reviewers must still assess whether the reply answers the question and avoids an extra confirmation.
BOOKING_EXPECTATIONS = {(1, 1): 'now', (3, 1): 'none', (3, 2): 'none', (3, 3): 'now'}


# Human semantic review, not keyword matching or mock-model acceptance. Turn 0 is /start.
DISCOVERY_REVIEW = {
    4: {
        'earlyTurns': [1, 2, 3, 4],
        'criteria': 'No unsolicited trial invitation before level is explored. Ask one relevant missing topic; '
                    'do not repeat known answers. After turn 5 accept unknown level, explain personal relevance, '
                    'offer now and later and disclose unavailable launch. Never invent measured CEFR.',
    },
    5: {'criteria': 'All five topics are supplied in turn 1. Do not ask them again or force five turns. '
                    'A relevant invitation may follow immediately, with now/later and launch limitation.'},
    6: {'criteria': 'Turn 1: explore missing topics without pitching. Turn 2: book now immediately, '
                    'without completing discovery or asking another confirmation.'},
    7: {'criteria': 'Explain that a structured beginner/grammar course is outside the format. '
                    'Do not promise it, invent exclusion rules, or push a mismatched trial. Keep talking.'},
    8: {'criteria': 'Unknown level and refusal of a test count as an answer. Do not test or ask again. '
                    'Use known goal, result, pain and experience; an invitation may offer now/later.'},
}
BOOKING_EXPECTATIONS.update({(6, 1): 'none', (6, 2): 'now'})


def check_booking_expectation(expected, state):
    booking = active_booking(state)
    if expected == 'none':
        return booking is None
    return booking is not None and booking['timing'] == 'now'


async def main():
    settings=Settings.from_env()
    if not settings.openai_api_key:
        raise SystemExit('OPENAI_API_KEY is not configured; real-model probe not run.')
    client=AsyncOpenAI(api_key=settings.openai_api_key,base_url=settings.openai_base_url,max_retries=0)
    model=TutorModel(OpenAiChatModel(client,settings.kora_model))
    reports=[]
    degraded=False
    contract_failed=False
    try:
        for i,scenario in enumerate(SCENARIOS):
            store=TutorStore();runtime=TutorRuntime(store,model);learner=f'synthetic-tutor-{i}'
            turns=[]
            for j,text in enumerate(['/start',*scenario,'/start']):
                answer=await runtime.respond(TutorRequest(sessionId=learner,requestId=str(j),text=text,receivedAt=time.time()),start=text=='/start')
                turn=dict(user=text,response=answer)
                expected=BOOKING_EXPECTATIONS.get((i,j))
                if expected:
                    passed=check_booking_expectation(expected,await store.load(learner))
                    turn['bookingCheck']=dict(expected=expected,passed=passed)
                    turn['reviewCriteria']='Clear request: book now without timezone or another confirmation; hypothetical/availability question: answer without booking. Always disclose unavailable lesson launch plainly.'
                    contract_failed |= not passed
                turns.append(turn)
                print(f'scenario={i} turn={j} degraded={answer["degraded"]}', file=sys.stderr, flush=True)
                if answer['degraded']:
                    degraded=True
                    break
            state=await store.load(learner)
            reports.append(dict(discoveryReview=DISCOVERY_REVIEW.get(i),turns=turns,bookings=state['bookings'],personal=state['personal'],learning=state['learning'],events=state['events']))
            if degraded:
                break
    finally:
        await client.close()
    print(json.dumps(reports,ensure_ascii=False,indent=2))
    if degraded:
        raise SystemExit('Live probe incomplete: degraded response; inspect provider access and saved outcomes before retrying.')
    if contract_failed:
        raise SystemExit('Live probe failed a stored booking expectation; review the transcript before claiming acceptance.')

if __name__=='__main__': asyncio.run(main())
