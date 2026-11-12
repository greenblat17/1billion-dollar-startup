"""Native billing tools cannot confirm payment or choose a price."""
from pydantic import BaseModel, ConfigDict, Field


class Checkout(BaseModel):
    model_config = ConfigDict(extra='forbid', strict=True)
    evidence: str = Field(min_length=1, max_length=2000)


class CancelRenewal(Checkout):
    subscriptionId: str
    expectedVersion: int = Field(ge=1)


SUBSCRIPTION_MODELS = {
    'request_subscription_payment': (Checkout, 'subscription.checkout'),
    'cancel_subscription_renewal': (CancelRenewal, 'subscription.cancel_renewal'),
}
SUBSCRIPTION_TOOLS = [
    {'type': 'function', 'function': {'name': 'get_subscription',
     'description': 'Read authoritative subscription status, dates, auto-renewal and configured terms. Never invent prices or claim payment from user text.',
     'parameters': {'type': 'object', 'properties': {}, 'required': [], 'additionalProperties': False}, 'strict': True}},
    {'type': 'function', 'function': {'name': 'request_subscription_payment',
     'description': 'Send a payment card in Telegram on a clear request to pay. Backend supplies fixed terms and immediate start disclosure before the payment button. Current provider is explicitly MOCK; no real charge. Does not activate subscription. No second confirmation for a clear request.',
     'parameters': Checkout.model_json_schema(), 'strict': True}},
    {'type': 'function', 'function': {'name': 'cancel_subscription_renewal',
     'description': 'Disable auto-renewal on a clear request without cancelling the paid period or lessons. Read subscriptionId and version first.',
     'parameters': CancelRenewal.model_json_schema(), 'strict': True}},
]
