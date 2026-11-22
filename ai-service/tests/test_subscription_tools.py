from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.foundation_tutor import FoundationTutor
from app.subscription_tools import SUBSCRIPTION_TOOLS
from app.trial_tools import TrialTutorModel
from app.tutor import TutorRequest


@pytest.mark.asyncio
async def test_payment_request_uses_backend_terms_and_cannot_supply_price_or_success():
    backend = SimpleNamespace(command=AsyncMock(return_value={'id': 'checkout', 'mock': True}))
    tutor = FoundationTutor(backend, None, None)
    source = {'id': 'event', 'body': 'Пришли оплату'}
    args = {'evidence': 'Пришли оплату'}
    first = await tutor.execute('scope', 'request', source, 'request_subscription_payment', args)
    second = await tutor.execute('scope', 'request', source, 'request_subscription_payment', args)
    assert first == second == {'ok': True, 'result': {'id': 'checkout', 'mock': True}}
    assert backend.command.await_args_list[0] == backend.command.await_args_list[1]
    assert backend.command.await_args.args[2:] == ('subscription.checkout', {'sourceId': 'event', **args})
    for extra in [{'amount': 1}, {'success': True}, {'studentId': 'other'}]:
        result = await tutor.execute('scope', 'request', source, 'request_subscription_payment', args | extra)
        assert result['ok'] is False
    assert backend.command.await_count == 2


@pytest.mark.asyncio
async def test_tools_expose_read_checkout_cancel_but_not_confirmation():
    llm = SimpleNamespace(complete_tools=AsyncMock(return_value={}))
    await TrialTutorModel(llm).complete([])
    tools = llm.complete_tools.await_args.args[1]
    names = {tool['function']['name'] for tool in tools}
    assert {t['function']['name'] for t in SUBSCRIPTION_TOOLS} <= names
    assert not any('confirm_payment' in name for name in names)
    backend = SimpleNamespace(command=AsyncMock(return_value={'status': 'expired'}))
    tutor = FoundationTutor(backend, None, None)
    result = await tutor.execute('scope', 'r', {'id': 'e', 'body': 'я оплатил'}, 'get_subscription', {})
    assert result['status'] == 'expired'
    assert backend.command.await_args.args[2] == 'subscription.get'


@pytest.mark.asyncio
async def test_committed_checkout_recovers_without_model_or_second_creation():
    backend = SimpleNamespace(command=AsyncMock(side_effect=[
        {'status': 'running', 'input': {'id': 'e', 'body': 'Пришли оплату'},
         'committed': [{'action': 'subscription.checkout', 'result': {'id': 'checkout'}}]},
        {'conversationId': 'c', 'reply': 'Запрос по подписке сохранён.'}]))
    context = SimpleNamespace(history=[], instructions=lambda purpose: '')
    model = SimpleNamespace(complete=AsyncMock())
    tutor = FoundationTutor(backend, model, SimpleNamespace(build=AsyncMock(return_value=context)))
    await tutor.respond(TutorRequest(sessionId='tg-100', requestId='r', text='Пришли оплату', receivedAt=1), 'scope')
    model.complete.assert_not_awaited()
    assert [call.args[2] for call in backend.command.await_args_list] == ['run.claim', 'run.complete']
    assert 'подписке' in backend.command.await_args.args[3]['reply']
