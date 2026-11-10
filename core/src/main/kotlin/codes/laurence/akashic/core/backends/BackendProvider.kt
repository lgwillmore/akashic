package codes.laurence.akashic.core.backends

import codes.laurence.akashic.core.checkpoint.Checkpoint

interface BackendProvider<CHECKPOINT : Checkpoint> :
    AggregateStackProvider<CHECKPOINT>, SubscriptionRunnerProvider<CHECKPOINT>
