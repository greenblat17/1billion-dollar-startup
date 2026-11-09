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
]

# Indices include the initial /start. These inspect stored outcomes, not reply wording.
# Reviewers must still assess whether the reply answers the question and avoids an extra confirmation.
BOOKING_EXPECTATIONS = {(1, 1): 'now', (3, 1): 'none', (3, 2): 'none', (3, 3): 'now'}


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
            reports.append(dict(turns=turns,bookings=state['bookings'],personal=state['personal'],learning=state['learning'],events=state['events']))
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
