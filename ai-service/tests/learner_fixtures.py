import json
from copy import deepcopy

async def seed_history(store, session, state):
    if store._redis:
        await store._redis.set(f"onboarding:{session}", json.dumps(state))
    else:
        store._memory[session] = deepcopy(state)
