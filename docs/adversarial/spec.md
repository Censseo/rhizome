# Adversarial Test Protocol

> The catalogue of exploit scenarios Rhizome is tested against, and the rule that keeps the
> catalogue honest: every scenario names a proof, and `AdversarialProtocolTest` fails the build
> when a named proof no longer exists.
> **Status**: Active — this document is machine-checked.

## Overview

Rhizome's security model (`WHITEPAPER.md` §7) is written as prose: a list of defended vectors and
load-bearing invariants accumulated over successive review passes. Prose does not fail a build. A
rule can be relaxed during a refactor, a test can be renamed out of relevance, and the paragraph
that claims the defence keeps reading exactly as before.

This protocol closes that gap. It is a catalogue of **exploit scenarios** — the concrete thing an
adversary tries, not the invariant it violates — and each scenario carries a **proof**: a test
method, by file and name, that executes the attack and asserts the outcome. The catalogue is
parsed by a test, so a proof that stops existing is a build failure rather than a stale document.

What this is not: a penetration-testing plan, a fuzzing campaign, or a substitute for review. It is
the regression floor. It says *these attacks have been tried, here is where the code refuses them,
and here is what will tell you when that stops being true.*

## Scope

- **In scope** — the consensus gate, the transaction and issuance rules, the contract sandbox, the
  synchronisation protocol, the peer transport, the node's HTTP surface, wire decoding, the
  persistence layer's crash and reorg behaviour, and the wallet's key handling.
- **Out of scope** — the operator's host security, transport-layer TLS termination (the node is
  designed to sit behind a reverse proxy for that), and economic modelling of mining incentives
  beyond the issuance rules a node enforces.

## Adversary model

Scenarios are classified by the *least* capability that suffices to attempt them. A defence that
only holds against a weaker adversary than the one who can reach it is not a defence.

| Class | Capability |
|-------|------------|
| **A0** | **Observer.** Reads the public API and the chain. No ability to write anything. |
| **A1** | **Client.** Can submit transactions and arbitrary HTTP requests, at scale, from many addresses. Holds keys only to its own accounts. |
| **A2** | **Peer.** Speaks the p2p protocol and may answer any request with anything — lies, oversized bodies, silence, or a slow trickle. May run many identities. |
| **A3** | **Minority miner.** A2, plus the ability to produce valid proof of work for a minority of blocks, and therefore to choose what goes in its own blocks and when to release them. |
| **A4** | **Majority miner.** A3 with sustained majority hash power. |
| **A5** | **Browser-adjacent.** Can get the operator's browser to load a page the attacker controls, or control DNS for a name the operator's browser resolves. |
| **A6** | **Host events.** Power loss, process kill, partial disk writes. Not a person, but adversarial in the same sense: the worst-timed interruption. |

## Protocol rules

1. **A scenario is an attack, not an assertion.** "Rejects a negative amount" is an assertion;
   "an attacker mints coins by sending a negative amount" is a scenario. The scenario names the
   attacker's goal, so a reader can tell whether the goal is still unreachable after a refactor
   that moved the check.
2. **The attack must reach the gate it names.** A forged block whose proof of work was invalidated
   by the forgery is rejected at the PoW gate, whatever it was aimed at — the test then passes
   while proving nothing. `BlockForge.seal()` re-mines after every mutation for this reason, and a
   scenario asserts the *exact* rejection status rather than "not success".
3. **Refusal must be free.** For every scenario whose expected outcome is rejection, the proof also
   asserts that the victim's state did not move: no value transferred, no chain truncation, no
   supply change. Rhizome's history is full of vectors where the rejection was correct and the
   *cost* of rejecting was the attack (a pop/restore cycle per round, memory-hard hashing before
   the cheap checks).
4. **A residual is declared, not hidden.** A scenario that cannot be defended is listed with
   verdict `RESIDUAL` and the bound that limits it. An undeclared residual is the failure mode this
   protocol exists to prevent.
5. **Every proof is machine-checked, in both directions.** `AdversarialProtocolTest` parses the
   tables below and fails if an ID is malformed, duplicated or non-dense within its family, a
   verdict is unknown, a family is undeclared, or a named proof does not resolve to a test method
   that is declared and not `@Disabled` — comments are stripped first, so a commented-out
   declaration cannot satisfy a reference. It also checks the reverse: every `@Test` in an attack
   suite must open its javadoc with the scenario id it runs, and the catalogue must list that test
   under that id. The reverse direction exists because label drift is silent by construction — the
   labels are comments — and it happened in four of six suites on this protocol's first commit.
6. **New consensus code adds a scenario.** A change to a validation gate, an issuance rule, a sync
   decision or the sandbox is incomplete until the catalogue names the attack it forecloses.

### Verdicts

| Verdict | Meaning | Proof requirement |
|---------|---------|-------------------|
| `DEFENDED` | The attack fails outright. | One or more test methods that run it. |
| `BOUNDED` | The attack is possible but its effect is capped by a rule. | One or more test methods that pin the cap, on both sides where the cap is a boundary. |
| `RESIDUAL` | Not defended. Accepted, with a stated bound. | A note; no test asserts the absence of the vector. |
| `GAP` | Identified, not yet proven. | Listed in [Known gaps](#known-gaps) with why. |

### Proof levels

A verdict says whether an attack fails; it does not say *where* the code that refuses it was
exercised. Three levels appear in this catalogue, and they answer different questions:

| Level | What runs | What it can assert |
|-------|-----------|--------------------|
| **component** | An engine, a VM or a store in-process, with its collaborators real but its transport absent. | The exact gate that refused, by status code — `INVALID_TRANSACTION_NONCE`, not merely "rejected". |
| **surface** | The real HTTP servlet or a real socket, driven with real requests. | That a forged header, an oversized body or a malformed request is handled as designed on the wire. |
| **network** | Assembled `RhizomeNode` processes: RocksDB on disk, an HTTP server on a port, a producer thread, sync loops. | Only what an outside observer sees — a height, a tip hash, a balance, a status — but about the *system*, including its threading, durability and recovery. |

The two are complements, not substitutes. A component test can prove a rule and still miss that the
assembled node never reaches it; a network test can prove the node held its chain and never tell
you which rule saved it.

The distribution is deliberate and worth stating, because "160 DEFENDED" reads as if it were
uniform: of the 171 scenarios, **116** rest at component level, **13** at the surface, **36** reach
the network, and 3 are residuals with no proof by definition. The network figure is the `E2E`
family plus the scenarios elsewhere that gained a second, network-level proof. Component level
dominates on purpose — it is the only level that can name the gate that refused — but a rule with
no network proof anywhere is a rule nobody has watched an assembled node apply.

### Families

`CONS` block validation gate ·
`POW` proof of work and difficulty ·
`TIME` timestamps ·
`MERKLE` transaction commitment ·
`SIG` authorisation and keys ·
`REPLAY` replay and double-spend ·
`INFL` issuance and ledger arithmetic ·
`UNCLE` GHOST uncle rewards ·
`REORG` fork choice, finality, synchronisation ·
`POOL` mempool and relay policy ·
`VM` contract sandbox and determinism ·
`STATE` authenticated state and snapshots ·
`PERS` persistence, reorg reversal, crash consistency ·
`NET` peer transport and discovery ·
`API` node HTTP surface ·
`CODEC` wire decoding bounds ·
`WALLET` client key handling ·
`E2E` the assembled node and network

## Running the protocol

```bash
./gradlew adversarial          # the protocol gate plus the dedicated attack suites
./gradlew :app-node:test --tests "rhizome.adversarial.e2e.*"   # the network layer alone
./gradlew test                 # every proof this catalogue cites
```

The `E2E` suites start real nodes and mine real blocks, so they cost roughly a minute of wall clock
between them — against a second or so for the whole component layer. That ratio is the reason the
catalogue is not written at network level throughout: the layer buys assurance about the assembled
system, and it buys it at a price that only makes sense for the properties nothing else can reach.

The suites written specifically for this protocol live in `lib-core/src/test/java/rhizome/adversarial/`
and share the fixtures in `lib-core/src/testFixtures/java/rhizome/adversarial/`
(`AdversarialChain`, `BlockForge`, `AdversarialPeer`). Most proofs, however, point at the tests that
already covered a vector where it lives — a scenario is satisfied by the test that runs it, not by
one written under this directory.

That has a consequence worth stating plainly: **`./gradlew adversarial` does not execute the
catalogue.** It runs the gate and the dozen-odd suites written here, which is a small fraction of
the ~90 files cited as proofs; the rest are ordinary tests that `./gradlew test` runs beside the
code they cover. The gate is what makes that safe — it fails if any cited proof has been renamed,
deleted or disabled, so "the catalogue is honest" is checked on every build even though the
catalogue is never run as one suite.

---

## CONS — block validation gate

| ID | Scenario | Class | Verdict | Proof |
|----|----------|-------|---------|-------|
| CONS-01 | Submit a block whose id does not follow the tip, to insert history at an arbitrary height. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/ChainEngineTest.java#rejectsBrokenChaining` |
| CONS-02 | Submit a block whose `lastBlockHash` points at another branch, the defect that forked Pandanite toward block ~7400. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/ChainEngineTest.java#rejectsBrokenChaining` |
| CONS-03 | Mine a block past a published checkpoint on a competing history. | A4 | DEFENDED | `lib-core/src/test/java/rhizome/HardeningTest.java#checkpointPinsHistory` |
| CONS-04 | Inflate a block past the size cap to make every node pay download and storage for it. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/BlockSizeLimitTest.java#rejectsBlockOverTheSizeCap` |
| CONS-05 | Hide a contract payload in the coinbase, whose kind was serialized independently of the fee flag, to desynchronise receipt counting and blow up a later reorg. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/ChainEngineTest.java#rejectsBlockWhoseCoinbaseCarriesAContractKind` |
| CONS-06 | Carry a miner vote outside the protocol domain to corrupt the parameter tally. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/VotingTest.java#consensusGateRejectsOutOfRangeVote` |
| CONS-07 | Break account-nonce sequencing inside a block to execute a sender's transactions out of order. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/ChainEngineTest.java#enforcesAccountNonceSequence` |
| CONS-08 | Submit a block containing a box or token transaction on a node that has no such processor, so validity depends on the node's wiring. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/core/blockchain/ChainEngineBootTest.java#aDomainThatWasNeverNamedRejectsItsTransactionsInsteadOfIgnoringThem` |
| CONS-09 | Force a validating node to run unbounded VM work by mining a free, enormous `gasLimit` call into a block ("poison block"). | A3 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/ContractConsensusTest.java#contractTxOverPerTxGasCapIsRejectedBeforeExecution`, `lib-vm/src/test/java/rhizome/vm/ContractConsensusTest.java#perTxUnderCapButBlockGasSumOverCapIsRejected`, `app-node/src/test/java/rhizome/adversarial/e2e/E2EContractTest.java#aPoisonBlockPushedAtTheSubmitRouteIsRefusedAndTheNodeStaysHealthy` |
| CONS-10 | Exploit the difference between a node's rejection order and its cost by making an invalid block expensive to reject. | A1 | DEFENDED | `lib-core/src/test/java/rhizome/core/blockchain/BlockUnclesTest.java#blocksOwnPowIsVerifiedBeforeUncleWork` |

## POW — proof of work and difficulty

| ID | Scenario | Class | Verdict | Proof |
|----|----------|-------|---------|-------|
| POW-01 | Submit a block claiming a difficulty it never paid for. | A2 | DEFENDED | `lib-core/src/test/java/rhizome/ChainEngineTest.java#rejectsBadMerkleAndBadPow` |
| POW-02 | Declare a difficulty other than the one history dictates, to mine cheaply while looking valid. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/HeaderChainTest.java#rejectsWrongDifficulty` |
| POW-03 | Keep a stale difficulty across a `popBlock`, the Pandanite defect that forced a hard-coded exception for blocks 536100–536200. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/DifficultyRetargetTest.java#difficultyIsRecomputedExactlyAfterAPopAcrossARetargetBoundary` |
| POW-04 | Swing difficulty arbitrarily in one retarget by manipulating the observed window. | A3 | BOUNDED | `lib-core/src/test/java/rhizome/DifficultyAdjustmentTest.java#stepIsBoundedAgainstTimestampManipulation`, `lib-core/src/test/java/rhizome/DifficultyAdjustmentTest.java#clampedToNetworkBounds` |
| POW-05 | Sustain a timestamp-compression campaign across several retarget windows to drive difficulty out of its bounds and price honest miners out. | A3 | BOUNDED | `lib-core/src/test/java/rhizome/adversarial/TimestampAttackTest.java#aSustainedMinimalTimestampCampaignMovesDifficultyOnlyAtTheBoundedRate` |
| POW-06 | Mine under one PoW cost schedule and have it verified under another, across an upgrade boundary. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/PowUpgradeTest.java#blockAfterBoundaryMinedWithOldCostsIsRejected`, `lib-core/src/test/java/rhizome/PowUpgradeTest.java#blockBeforeBoundaryMinedWithNewCostsIsRejected` |
| POW-07 | Feed the PoW entry point out-of-range cost parameters to trigger a remote arithmetic fault. | A2 | DEFENDED | `lib-crypto/src/test/java/rhizome/crypto/PowCostsTest.java#pufferfishEntryPointRejectsOverflowingCostT`, `lib-crypto/src/test/java/rhizome/crypto/PowCostsTest.java#pufferfishEntryPointRejectsOutOfRangeCostM` |

## TIME — timestamps

| ID | Scenario | Class | Verdict | Proof |
|----|----------|-------|---------|-------|
| TIME-01 | Pre-mine a branch into the future and release it to force a reorg. | A3 | BOUNDED | `lib-core/src/test/java/rhizome/adversarial/TimestampAttackTest.java#anAttackerCanPreStampOnlyAsManyBlocksAsTheFutureWindowHoldsBlocks`, `lib-core/src/test/java/rhizome/MinBlockTimeTest.java#futureBoundCapsBlocksMinedInAdvance` |
| TIME-02 | Antedate a block below median-time-past to pass a stale branch off as contemporaneous, or below its own parent. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/adversarial/TimestampAttackTest.java#aBlockBelowEitherTimestampFloorIsRefused` |
| TIME-03 | Push a boundary block's timestamp far out to drag the next window's difficulty down — the classic time-warp. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/HeaderChainTest.java#inflatedBoundaryTimestampNoLongerDragsDifficultyDown` |
| TIME-04 | Mine blocks faster than the consensus cadence floor allows, out-producing the rest of the network. | A4 | DEFENDED | `lib-core/src/test/java/rhizome/MinBlockTimeTest.java#rejectsBlockTooCloseToParent` |
| TIME-05 | Stamp a pooled transaction far in the future so it sits in the pool as permanently fresh junk. | A1 | DEFENDED | `lib-core/src/test/java/rhizome/AdmissionParityTest.java#futureNonceAgrees`, `lib-core/src/test/java/rhizome/MemPoolTest.java#parkedTransactionsExpireAfterTheTtl` |

## MERKLE — transaction commitment

| ID | Scenario | Class | Verdict | Proof |
|----|----------|-------|---------|-------|
| MERKLE-01 | Exploit the odd-level duplication so two bodies share a root (CVE-2012-2459), then swap the body under a mined header. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/adversarial/MerkleForgeryAttackTest.java#duplicatingTheLastTransactionYieldsTheIdenticalMerkleRoot`, `lib-core/src/test/java/rhizome/adversarial/MerkleForgeryAttackTest.java#theForgedBodyDoesNotInheritTheVictimBlocksHashSoItsProofOfWorkDoesNotCarry` |
| MERKLE-02 | Mine the duplicated-transaction body properly, so the proof of work is genuine. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/adversarial/MerkleForgeryAttackTest.java#aFullyMinedDuplicateTransactionBlockIsStillRefusedAndTheChainDoesNotMove`, `lib-core/src/test/java/rhizome/ExecutorTest.java#rejectsDuplicateInBlock` |
| MERKLE-03 | Reorder a block's transactions, which a sorting Merkle would leave hash-equivalent while nonce validation reads them differently — a chain split rather than a rejected block. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/adversarial/MerkleForgeryAttackTest.java#reorderingTheBodyChangesTheRootSoOrderIsCommittedByTheProofOfWork`, `lib-core/src/test/java/rhizome/MerkleTreeTest.java#rootCommitsToTransactionOrder` |
| MERKLE-04 | Forge a leaf that is really an internal node, the second-preimage attack domain separation exists to stop. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/MerkleTreeTest.java#leafAndNodeDomainsAreSeparated` |
| MERKLE-05 | Submit a block whose declared root does not match its body. | A2 | DEFENDED | `lib-core/src/test/java/rhizome/ChainEngineTest.java#rejectsBadMerkleAndBadPow` |

## SIG — authorisation and keys

| ID | Scenario | Class | Verdict | Proof |
|----|----------|-------|---------|-------|
| SIG-01 | Malleate an Ed25519 signature to obtain a second identity for one authorised spend, and execute it twice (Pandanite #37). | A0 | DEFENDED | `lib-core/src/test/java/rhizome/adversarial/SignatureForgeryAttackTest.java#malleatingTheSignatureDoesNotProduceASecondTransactionIdentity`, `lib-core/src/test/java/rhizome/adversarial/SignatureForgeryAttackTest.java#theMalleatedEncodingDoesNotVerify` |
| SIG-02 | Spend the same funds twice via the malleated twin. | A0 | DEFENDED | `lib-core/src/test/java/rhizome/adversarial/SignatureForgeryAttackTest.java#theMalleatedTwinCannotSpendTheSameFundsASecondTime` |
| SIG-03 | Name a victim as sender while signing with the attacker's own key. | A1 | DEFENDED | `lib-core/src/test/java/rhizome/adversarial/SignatureForgeryAttackTest.java#namingAVictimAsSenderWhileSigningWithAnotherKeyIsRefused`, `lib-core/src/test/java/rhizome/ExecutorTest.java#rejectsSpoofedSender` |
| SIG-04 | Raise the amount on an authorised transaction after it was signed. | A2 | DEFENDED | `lib-core/src/test/java/rhizome/adversarial/SignatureForgeryAttackTest.java#raisingTheAmountAfterSigningBreaksTheSignature`, `lib-core/src/test/java/rhizome/ExecutorTest.java#rejectsTamperedSignature` |
| SIG-05 | Poison the signature verifier's cache so a bad signature is later accepted as verified. | A1 | DEFENDED | `lib-core/src/test/java/rhizome/SignatureVerifierTest.java#markVerifiedReVerifiesAndRefusesToPoisonTheCacheWithABadSignature`, `lib-core/src/test/java/rhizome/SignatureVerifierTest.java#cacheKeyBindsSignatureSoMalleatedSigMisses` |
| SIG-06 | Authorise with a small-order or non-canonical public key, which verifies against anything. | A1 | DEFENDED | `lib-crypto/src/test/java/rhizome/crypto/PublicKeyTest.java#smallOrderEncodingsAllMapToEmpty`, `lib-crypto/src/test/java/rhizome/crypto/PublicKeyTest.java#nonCanonicalYIsRejected`, `lib-crypto/src/test/java/rhizome/crypto/PublicKeyTest.java#offCurvePointIsRejected` |
| SIG-07 | Downgrade a post-quantum-committed transaction's scheme byte, stripping the commitment while keeping the signature. | A2 | DEFENDED | `lib-core/src/test/java/rhizome/TransactionSchemeAgilityTest.java#schemeDowngradeKeepsTheSignatureButBreaksTheSenderBinding`, `lib-core/src/test/java/rhizome/TransactionSchemeAgilityTest.java#forgingACommitmentBreaksTheSenderBinding` |
| SIG-08 | Drain an arbitrary wallet through the one self-authorised transaction kind, `BOX_COLLECT`, by naming a funded sender. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/BoxConsensusTest.java#maliciousBoxCollectCannotDrainAnArbitraryWallet` |
| SIG-09 | Forge an address whose checksum hides a substituted version byte or body. | A1 | DEFENDED | `lib-core/src/test/java/rhizome/PublicAddressSchemeTest.java#checksumCoversTheVersionByte`, `lib-core/src/test/java/rhizome/PublicAddressSchemeTest.java#checksumStillCatchesBodyTypos` |

## REPLAY — replay and double-spend

| ID | Scenario | Class | Verdict | Proof |
|----|----------|-------|---------|-------|
| REPLAY-01 | Resubmit a confirmed transaction on the same branch. | A0 | DEFENDED | `lib-core/src/test/java/rhizome/adversarial/ReplayAttackTest.java#resubmittingAConfirmedTransactionIsRefused`, `lib-core/src/test/java/rhizome/ExecutorTest.java#rejectsAlreadyExecuted` |
| REPLAY-02 | Reuse a spent nonce for a different payment. | A1 | DEFENDED | `lib-core/src/test/java/rhizome/adversarial/ReplayAttackTest.java#aDifferentTransactionReusingASpentNonceIsRefused` |
| REPLAY-03 | Replay a transaction captured on one network onto another. | A0 | DEFENDED | `lib-core/src/test/java/rhizome/adversarial/ReplayAttackTest.java#aTransactionSignedForAnotherNetworkCarriesNoAuthorityHere`, `lib-core/src/test/java/rhizome/ExecutorTest.java#rejectsWrongChainId` |
| REPLAY-04 | Have a transaction mined into a branch, orphan that branch, and either double-pay it on the winner or leave it permanently unspendable. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/adversarial/ReplayAttackTest.java#aTransactionUndoneByAReorgIsSpendableAgainAndPaysExactlyOnce`, `app-node/src/test/java/rhizome/adversarial/e2e/E2EDoubleSpendTest.java#aPaymentUndoneByAReorgCanBeMinedAgainOnTheWinningBranchAndPaysOnce` |
| REPLAY-05 | Spend the same balance twice within one block. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/ExecutorTest.java#insufficientBalanceRollsBackTheWholeBlock`, `lib-core/src/test/java/rhizome/MemPoolTest.java#enforcesCumulativeBalanceNotPerTransaction` |

## INFL — issuance and ledger arithmetic

| ID | Scenario | Class | Verdict | Proof |
|----|----------|-------|---------|-------|
| INFL-01 | Pay yourself more than the schedule in the coinbase. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/adversarial/InflationAttackTest.java#aCoinbaseAboveTheScheduleIsRefused`, `lib-core/src/test/java/rhizome/ExecutorTest.java#rejectsWrongReward` |
| INFL-02 | Include a second coinbase to double the block's issuance. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/adversarial/InflationAttackTest.java#aSecondCoinbaseIsRefused`, `lib-core/src/test/java/rhizome/ExecutorTest.java#rejectsDuplicateCoinbase` |
| INFL-03 | Omit the coinbase, or pay less than the schedule, to produce a block that is valid on some nodes and not others. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/adversarial/InflationAttackTest.java#aBlockWithoutACoinbaseIsRefused`, `lib-core/src/test/java/rhizome/adversarial/InflationAttackTest.java#aCoinbaseBelowTheScheduleIsRefusedToo` |
| INFL-04 | Mint money with a negative amount or fee, inverting the ledger's arithmetic. | A1 | DEFENDED | `lib-core/src/test/java/rhizome/ChainEngineTest.java#rejectsNegativeAmountThroughFullConsensusPath`, `lib-core/src/test/java/rhizome/ExecutorTest.java#rejectsNegativeFeeThatWouldMintMoney` |
| INFL-05 | Underflow a balance through an unchecked subtraction — the `invalid.json` incident, where `BALANCE_TOO_LOW` transactions were accepted network-wide. | A1 | DEFENDED | `lib-persistence/src/test/java/rhizome/RocksDbNodeStoreTest.java#ledgerChecksArithmetic`, `lib-core/src/test/java/rhizome/ExecutorTest.java#insufficientBalanceRollsBackTheWholeBlock` |
| INFL-06 | Overflow a wallet's 64-bit balance with a large deposit, leaving a partial mutation behind. | A1 | DEFENDED | `lib-core/src/test/java/rhizome/ExecutorTest.java#depositOverflowRollsBackCleanlyInsteadOfCorruptingState` |
| INFL-07 | Break supply conservation on a fee path, so a valid block issues more than the reward. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/adversarial/InflationAttackTest.java#aValidBlockRaisesTheTotalSupplyByExactlyTheScheduledReward` |
| INFL-08 | Exploit a floating-point reward computation so two implementations disagree and the chain splits (Pandanite §4.2). | A3 | DEFENDED | `lib-core/src/test/java/rhizome/NetworkParametersTest.java#miningRewardIsIntegerAndDeterministic` |
| INFL-09 | Mint permanent ledger entries at zero cost with amount-0, fee-0 transfers. | A1 | DEFENDED | `lib-core/src/test/java/rhizome/ExecutorTest.java#zeroAmountDepositDoesNotCreateTheRecipientWallet`, `lib-core/src/test/java/rhizome/ExecutorTest.java#consensusFeeFloorRejectsFreeTransactionsEvenInAMinersOwnBlock` |
| INFL-10 | Seed a genesis ledger with negative or high-bit-set balances. | A6 | DEFENDED | `lib-core/src/test/java/rhizome/LedgerSnapshotTest.java#genesisLedgerRejectsNegativeSeededBalances`, `lib-core/src/test/java/rhizome/LedgerSnapshotTest.java#rejectsUnsignedAmountsWithTheHighBitSet` |

## UNCLE — GHOST uncle rewards

| ID | Scenario | Class | Verdict | Proof |
|----|----------|-------|---------|-------|
| UNCLE-01 | Attach cheap minimum-difficulty orphans to a real block to mint roughly half a block reward each. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/core/blockchain/BlockUnclesTest.java#subDifficultyUncleRewardIsScaledAndExactlyReversedOnPop`, `lib-core/src/test/java/rhizome/core/blockchain/ExecutorRewardScalingTest.java#eachMissingDifficultyBitHalvesTheReward` |
| UNCLE-02 | Claim an inflated difficulty on an uncle reference to mint more work and reward than it proves. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/core/blockchain/BlockUnclesTest.java#rejectsUncleWithInflatedDifficulty` |
| UNCLE-03 | Reference an uncle that does not exist, is a main-chain block, or is a duplicate. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/core/blockchain/BlockUnclesTest.java#rejectsUnknownUncle`, `lib-core/src/test/java/rhizome/core/blockchain/BlockUnclesTest.java#rejectsUncleThatIsAMainChainBlock`, `lib-core/src/test/java/rhizome/core/blockchain/BlockUnclesTest.java#rejectsDuplicateUncles` |
| UNCLE-04 | Redirect an uncle's reward by forging its miner address. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/core/blockchain/BlockUnclesTest.java#rejectsUncleWithForgedMiner` |
| UNCLE-05 | Pad headers with in-range fake uncle references to inflate a branch's apparent work at the reorg gate and force a pop/restore cycle. | A2 | DEFENDED | `lib-core/src/test/java/rhizome/HeaderChainTest.java#committedUncleWorkDoesNotInflateTheReorgGateWork`, `lib-core/src/test/java/rhizome/core/blockchain/BlockUnclesTest.java#baseWorkExcludesUncleWorkThatTotalWorkIncludes` |
| UNCLE-06 | Exceed the per-block uncle count so a submitted block forces unbounded memory-hard hashing. | A1 | DEFENDED | `lib-core/src/test/java/rhizome/core/blockchain/BlockUnclesTest.java#rejectsTooManyUncles`, `lib-core/src/test/java/rhizome/BlockAssemblerUncleSizeTest.java#uncleBytesAreChargedAgainstTheSizeCap` |
| UNCLE-07 | Churn the bounded orphan pool so an honest node cannot restore its own suffix after a rejected reorg, forcing a full resync. | A2 | DEFENDED | `lib-core/src/test/java/rhizome/core/blockchain/BlockUnclesTest.java#restoreBlockRecoversANephewWhoseUncleIsMissingFromThePool`, `lib-core/src/test/java/rhizome/core/blockchain/BlockUnclesTest.java#restoreBlockStillEnforcesStructuralUncleBounds` |

## REORG — fork choice, finality, synchronisation

| ID | Scenario | Class | Verdict | Proof |
|----|----------|-------|---------|-------|
| REORG-01 | Rewrite recent history with sustained majority hash power. | A4 | RESIDUAL | Irreducible in any proof-of-work chain. Bounded in depth by `maxReorgDepth` (120 blocks, ≈10 min) — see REORG-02. |
| REORG-02 | Rewrite history deeper than the finality window by presenting a heavier branch. | A4 | BOUNDED | `lib-core/src/test/java/rhizome/adversarial/ReorgAttackTest.java#aHeavierWithheldBranchForkingExactlyAtTheWindowIsAdopted`, `lib-core/src/test/java/rhizome/adversarial/ReorgAttackTest.java#oneBlockPastTheWindowTheSameHeavierBranchIsRefused`, `lib-core/src/test/java/rhizome/HardeningTest.java#reorgDeeperThanFinalityWindowIsRefused` |
| REORG-03 | Make a refused reorg expensive: leave the victim truncated, degraded, or unable to keep mining. | A2 | DEFENDED | `lib-core/src/test/java/rhizome/adversarial/ReorgAttackTest.java#aRefusedDeepReorgLeavesTheNodeAbleToKeepMiningItsOwnBranch`, `lib-core/src/test/java/rhizome/ChainSynchronizerTest.java#fallbackGateRejectsWrongDifficultyBeforeAnyPop`, `app-node/src/test/java/rhizome/node/RhizomeNodeTest.java#aDeepForkedPeerIsRefusedButNeverBanned` |
| REORG-04 | Claim enormous cumulative work without proving any, to force a pop/restore cycle per round. | A2 | DEFENDED | `lib-core/src/test/java/rhizome/HardeningTest.java#claimedButUnprovenWorkCausesZeroStateMutation`, `lib-core/src/test/java/rhizome/HeaderSynchronizerTest.java#peerLyingAboutTotalWorkCostsOnlyHeaders`, `app-node/src/test/java/rhizome/adversarial/e2e/E2EHostilePeerTest.java#aPeerClaimingHugeWorkAndProvingNoneChangesNothing` |
| REORG-05 | Abandon the exchange mid-reorg, leaving the victim popped to the fork with a partial branch applied. | A2 | DEFENDED | `lib-core/src/test/java/rhizome/HeaderSynchronizerTest.java#aPeerThatGoesUnavailableMidBodyLeavesTheLocalBranchIntact`, `lib-core/src/test/java/rhizome/ChainSynchronizerPipelineTest.java#aTransportFailureMidRangeStaysUnavailableAndNeverReadsAsInvalid` |
| REORG-06 | Hold two equal-work camps split indefinitely, so neither side can ever rejoin. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/HeaderSynchronizerTest.java#equalBaseWorkAndEqualTotalResolvesDeterministically`, `lib-core/src/test/java/rhizome/HeaderSynchronizerTest.java#equalTotalTiebreakAlsoConvergesViaTheLegacyBlockFallback` |
| REORG-07 | Serve a branch from an incompatible genesis to make the victim adopt another network's history. | A2 | DEFENDED | `lib-core/src/test/java/rhizome/ChainSynchronizerTest.java#incompatibleGenesisIsRejected` |
| REORG-08 | Over-report height so the victim fetches an unbounded range. | A2 | DEFENDED | `lib-core/src/test/java/rhizome/ChainSynchronizerTest.java#extensionWindowIsCappedDespiteAnOverReportingPeer` |
| REORG-09 | Serve a malformed body window mid-sync to crash or wedge the sync pass. | A2 | DEFENDED | `lib-core/src/test/java/rhizome/HeaderSynchronizerTest.java#aMalformedBodyWindowIsThePeersFaultAndNeverEscapesTheSyncPass`, `lib-core/src/test/java/rhizome/UncleSyncRegressionTest.java#aServedOrphanWithBadProofOfWorkIsRejected` |
| REORG-10 | Get an honest peer banned by making local backpressure or a transport hiccup look like a protocol violation, then eclipse the victim. | A2 | DEFENDED | `lib-core/src/test/java/rhizome/ChainSynchronizerTest.java#localSaturationDuringSyncIsNotPeerInvalid`, `lib-core/src/test/java/rhizome/HeaderSynchronizerTest.java#localBackpressureMidBodyIsNotAPeerFaultOnTheHeadersPath` |

## POOL — mempool and relay policy

| ID | Scenario | Class | Verdict | Proof |
|----|----------|-------|---------|-------|
| POOL-01 | Fill the pool with free spam to censor honest traffic at zero marginal cost. | A1 | DEFENDED | `lib-core/src/test/java/rhizome/MemPoolTest.java#configuredMinFeeRejectsFreeTransactionsBoundingNonceDomainGrowth`, `lib-core/src/test/java/rhizome/MemPoolTest.java#enforcesSizeBound` |
| POOL-02 | Flood the pool from one account to crowd everyone else out. | A1 | DEFENDED | `lib-core/src/test/java/rhizome/MemPoolTest.java#enforcesPerSenderCapSoOneAccountCannotFloodThePool` |
| POOL-03 | Stuff the pool with nonce-gapped, never-minable transactions so it is permanently full. | A1 | DEFENDED | `lib-core/src/test/java/rhizome/MemPoolTest.java#readyTransactionDisplacesParkedDeadWeightWhenPoolIsFull`, `lib-core/src/test/java/rhizome/MemPoolTest.java#parkedNewcomerCannotChurnAFullParkedPool` |
| POOL-04 | Evict honest transactions with unsigned junk, making eviction a free unauthenticated lever. | A1 | DEFENDED | `lib-core/src/test/java/rhizome/MemPoolTest.java#invalidSignatureNewcomerCannotEvictParkedVictimsFromAFullPool` |
| POOL-05 | Buy block-selection priority with a declared gas budget that will never be spent. | A1 | DEFENDED | `lib-core/src/test/java/rhizome/MemPoolTest.java#declaredGasBudgetDoesNotBuySelectionPriority` |
| POOL-06 | Get a transaction admitted that consensus will reject, so it poisons every candidate block and halts production network-wide. | A1 | DEFENDED | `lib-core/src/test/java/rhizome/AdmissionParityTest.java#feeFloorBoundaryAgrees`, `lib-core/src/test/java/rhizome/AdmissionParityTest.java#gasCeilingBoundaryAgrees`, `lib-core/src/test/java/rhizome/MemPoolTest.java#rejectsBoxTransactionBeforeItsActivationHeight` |
| POOL-07 | Replace a live transaction by fee without paying more, or displace a parked one. | A1 | DEFENDED | `lib-core/src/test/java/rhizome/MemPoolTest.java#replaceByFeeRequiresAPositiveFeeToReplaceAFreeOne`, `lib-core/src/test/java/rhizome/MemPoolTest.java#replaceByFeeRefusesToReplaceAParkedTransaction` |
| POOL-08 | Grow the account-nonce state domain without bound by cycling one principal through fresh accounts. | A1 | RESIDUAL | One permanent leaf per account that has ever transacted; the domain cannot self-prune without reopening replay. Bounded economically by the optional `minFee` floor (off by default) and by block space — WHITEPAPER §7.6. |

## VM — contract sandbox and determinism

| ID | Scenario | Class | Verdict | Proof |
|----|----------|-------|---------|-------|
| VM-01 | Deploy a module using floating-point or vector-float opcodes, whose results differ per JVM, to fork the chain. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#rejectsAModuleUsingAFloatOpcode`, `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#rejectsAnF32ConstInAGlobalInitExpression` |
| VM-02 | Use WASM GC opcodes to allocate on the JVM heap outside every gas and memory budget. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#rejectsAGcArrayTypeInTheTypeSection`, `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#rejectsArrayNewDefaultInAFunctionBody`, `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#rejectsArrayNewDefaultInAGlobalInitExpression` |
| VM-03 | Blow up the parser before gas metering can apply, with declared counts far larger than the bytes present. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/WasmPreScanCountsTest.java#rejectsFunctypeWhoseParamCountExceedsTheBytesPresent`, `lib-vm/src/test/java/rhizome/vm/WasmPreScanCountsTest.java#rejectsDataSegmentDeclaringHugePayload`, `lib-vm/src/test/java/rhizome/vm/WasmPreScanCountsTest.java#rejectsElementSegmentDeclaringHugeInitializerCount` |
| VM-04 | Exhaust one node's heap with locals×depth recursion so it OOMs while another reverts — a heap-dependent fork. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/WasmLocalsGuardTest.java#localsHeavyRecursionTrapsOnTheDeterministicLocalsBudget`, `lib-vm/src/test/java/rhizome/vm/WasmLocalsGuardTest.java#localsBudgetRevertIsDeterministicWarmAndCold`, `lib-vm/src/test/java/rhizome/vm/WasmDepthLimitTest.java#deepRecursionRevertsDeterministicallyInsteadOfCrashing` |
| VM-05 | Make `gasUsed` depend on whether the module cache was warm, so nodes disagree on the fee and the state root. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/WasmVmTest.java#moduleParseGasIsChargedIdenticallyOnWarmAndColdCache` |
| VM-06 | Grow tables or memory past the metered caps, unmetered. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#rejectsATableWhoseUnboundedMaxCouldGrowUnmetered`, `lib-vm/src/test/java/rhizome/vm/WasmVmTest.java#rejectsModuleWhoseTablesAggregateOverTheCap`, `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#rejectsMemoryDeclaringTooManyInitialPages` |
| VM-07 | Buy expensive host work for flat gas — a long read, a big page grow, a long callee address. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#transferValueGasScalesWithTheReadLengthNotFlat`, `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#memoryGrowGasScalesWithThePageCountNotFlat`, `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#callContractGasScalesWithTheCalleeAddressLengthNotJustInput` |
| VM-08 | Import a host function outside the declared ABI to escape the sandbox. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#rejectsAnImportOutsideTheHostWhitelist`, `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#rejectsANonFunctionImportEvenWithAWhitelistedName` |
| VM-09 | Re-enter a contract mid-call to observe or mutate half-applied state. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/CrossContractTest.java#reentrancyIsRefusedAndTheCallerObservesTheFailure` |
| VM-10 | Keep a sub-call's writes after the caller reverts, or splice a failed sub-call's logs into the parent's. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/CrossContractTest.java#callerRevertAfterSuccessfulSubCallDiscardsSubCallWrites`, `lib-vm/src/test/java/rhizome/vm/LogOrderingTest.java#aFailedSubCallsLogsAreNeverSplicedIntoTheParents` |
| VM-11 | Overwrite a deployed contract by redeploying at its address. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/WasmContractProcessorPersistenceTest.java#deployOverAnExistingContractIsRejected` |
| VM-12 | Front-run a template contract's `init` to seize ownership between deploy and initialisation. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/TokenContractTest.java#initCannotBeFrontRunByANonDeployer`, `app-node/src/test/java/rhizome/node/DashboardTemplatesTest.java#theServedTokenBinaryCarriesTheDeployerFrontRunGuard` |
| VM-13 | Spend from an agent wallet without being its owner, or grant yourself a session key. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/AgentWalletTest.java#nonOwnerCannotGrantItselfASession`, `lib-vm/src/test/java/rhizome/vm/AgentWalletTest.java#ownerDrivesArbitraryCallsThroughTheWalletButOthersCannot` |
| VM-14 | Drain an AMM pool by swapping past the curve's slippage floor. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/TokenPairTest.java#swapBelowMinOutRevertsLeavingReservesIntact`, `lib-vm/src/test/java/rhizome/vm/TokenPairTest.java#swapBeyondTheAllowanceUnwindsBothLegs` |
| VM-15 | Turn a host storage failure into a revert, so a node-level fault becomes a consensus-visible outcome that differs per node. | A6 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/HostFaultTest.java#storageFailureDuringCallIsFatalNeverARevert`, `lib-vm/src/test/java/rhizome/vm/HostFaultTest.java#codeReadFailureDuringCallIsFatalNeverARevert` |
| VM-16 | Serve a dashboard contract template whose source does not describe the binary a deploy click installs. | A0 | DEFENDED | `app-node/src/test/java/rhizome/node/DashboardTemplatesTest.java#everyServedWasmMatchesTheAuditedFixtureByteForByte`, `app-node/src/test/java/rhizome/node/DashboardTemplatesTest.java#everyServedSourceMatchesTheAuditedOriginalByteForByte` |

## STATE — authenticated state and snapshots

| ID | Scenario | Class | Verdict | Proof |
|----|----------|-------|---------|-------|
| STATE-01 | Commit a state root that does not describe the state, so light clients are lied to. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/StateRootConsensusTest.java#tamperedStateRootIsRejectedWithRollback` |
| STATE-02 | Fabricate an inclusion proof against a committed root. | A2 | DEFENDED | `lib-core/src/test/java/rhizome/SparseMerkleTreeTest.java#fabricatedProofsDoNotVerifyAgainstTheCommittedRoot`, `lib-core/src/test/java/rhizome/SparseMerkleTreeTest.java#verifyReturnsFalseOnMalformedProofInsteadOfThrowing` |
| STATE-03 | Fork the chain on map iteration order by making the root depend on insertion order. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/SparseMerkleTreeTest.java#rootIsIndependentOfInsertionOrder` |
| STATE-04 | Poison a bootstrapping node with a tampered, truncated or padded snapshot. | A2 | DEFENDED | `lib-core/src/test/java/rhizome/StateSnapshotTest.java#tamperedFlippedByteIsRefused`, `lib-core/src/test/java/rhizome/StateSnapshotTest.java#droppedEntryIsRefused`, `lib-core/src/test/java/rhizome/StateSnapshotTest.java#smuggledExtraEntryIsRefused` |
| STATE-05 | Offer a snapshot pivot that is unburied, out of range, or would overflow the header window. | A2 | DEFENDED | `app-node/src/test/java/rhizome/node/SnapshotBootstrapRefusalTest.java#anUnburiedPivotIsRefused`, `app-node/src/test/java/rhizome/node/SnapshotBootstrapRefusalTest.java#aPivotThatWouldOverflowTheHeaderWindowIsRefused`, `app-node/src/test/java/rhizome/node/SnapshotBootstrapRefusalTest.java#anOutOfRangeChunkCountIsRefusedBeforeAnyFetch` |
| STATE-06 | Overwrite an existing node's local history via the bootstrap path. | A2 | DEFENDED | `app-node/src/test/java/rhizome/node/SnapshotBootstrapRefusalTest.java#aNonEmptyChainStoreIsARefusalToOverwriteLocalHistory` |

## PERS — persistence, reorg reversal, crash consistency

| ID | Scenario | Class | Verdict | Proof |
|----|----------|-------|---------|-------|
| PERS-01 | Leave contract, box or token state behind after a reorg, so a rewritten block leaves residue. | A3 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/ContractConsensusTest.java#popRevertsContractStateExactly`, `lib-core/src/test/java/rhizome/BoxConsensusTest.java#popRevertsBoxStateExactly`, `lib-core/src/test/java/rhizome/TokenConsensusTest.java#mintTransferBurnThenPop` |
| PERS-02 | Make apply and rollback inexact, so a reorg silently changes balances. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/core/blockchain/LedgerReversalExactnessTest.java#aBlockCarryingEveryDomainReversesExactly`, `lib-core/src/test/java/rhizome/core/blockchain/LedgerReversalExactnessTest.java#uncleRewardsReverseAtTheirWorkScaledAmount` |
| PERS-03 | Cut power mid-commit so a peripheral store ends up ahead of the chain height. | A6 | DEFENDED | `lib-core/src/test/java/rhizome/StateRootConsensusTest.java#bootReconciliationRewindsPeripheralStoresAheadOfTheChainHeight`, `lib-persistence/src/test/java/rhizome/RocksDbNodeStoreTest.java#chainStoreAppendPopIsAtomicAndIndexed`, `app-node/src/test/java/rhizome/adversarial/e2e/E2ENodeResilienceTest.java#aRestartOnTheSameDataDirectoryRestoresChainBalancesAndNonces` |
| PERS-04 | Corrupt a persisted undo journal so a restart either loses state or allocates unboundedly on it. | A6 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/WasmContractProcessorPersistenceTest.java#aCorruptJournalCountFailsCleanlyWithoutAGiantAllocation`, `lib-vm/src/test/java/rhizome/vm/WasmContractProcessorPersistenceTest.java#aTruncatedJournalFailsCleanly` |
| PERS-05 | Continue writing new tips after a failed revert or restore, entrenching state that boot recovery has not repaired. | A6 | DEFENDED | `lib-core/src/test/java/rhizome/core/blockchain/DegradedBarrierTest.java#degradedBarsEveryNewTipWriteAndATornPopIsNotRestoreClearable` |
| PERS-06 | Lose an unrelated store's mutations because there is no single cross-store atomic commit. | A6 | RESIDUAL | Each store commits its own mutations and undo journal in one fsynced `WriteBatch`; a power cut between two stores' commits is reconciled at boot by rewinding any store left ahead of the chain height (WHITEPAPER §6.2, §7.6) rather than prevented. |

## NET — peer transport and discovery

| ID | Scenario | Class | Verdict | Proof |
|----|----------|-------|---------|-------|
| NET-01 | Register an internal or cloud-metadata address as a peer, turning the node into an SSRF proxy. | A1 | DEFENDED | `lib-net/src/test/java/rhizome/net/PeerRegistrySecurityTest.java#ssrfClassifierRejectsInternalAndMetadataHosts`, `lib-net/src/test/java/rhizome/net/PeerRegistrySecurityTest.java#ipv6TransitionTunnelsAreRejected` |
| NET-02 | Rebind a peer's DNS name between validation and connection. | A1 | DEFENDED | `lib-net/src/test/java/rhizome/net/PeerHostsCacheBoundTest.java#failedResolutionsAreCachedWithAShortTtl`, `app-node/src/test/java/rhizome/node/NodeApiHardeningFlagsTest.java#xffHopResolutionUsesOnlyTheParsedLiteralNeverTheResolver` |
| NET-03 | OOM the node with a giant response body on an automatic discovery round. | A2 | DEFENDED | `lib-net/src/test/java/rhizome/net/PeerDiscoveryBodyBoundTest.java#oversizedPeersBodyIsRejectedNotBuffered`, `lib-net/src/test/java/rhizome/net/HttpPeerSourceTest.java#oversizedTotalWorkIsRejectedNotParsed`, `app-node/src/test/java/rhizome/adversarial/e2e/E2EHostilePeerTest.java#aPeerAnsweringWithAnEnormousScalarIsRejectedNotParsed` |
| NET-04 | Hold the sync thread with a slow trickle, or with an absurd `Retry-After`. | A2 | DEFENDED | `lib-net/src/test/java/rhizome/net/HttpPeerSourceTest.java#slowDripBodyHitsWholeExchangeDeadline`, `lib-net/src/test/java/rhizome/net/HttpPeerSourceThrottleTest.java#anAbsurdRetryAfterIsClampedSoAPeerCannotParkTheSyncThread` |
| NET-05 | Eclipse a node by filling its peer table from one subnet with many identities. | A2 | DEFENDED | `lib-net/src/test/java/rhizome/net/PeerRegistrySecurityTest.java#subnetBucketCapsDiscoveredPeersFromOneSubnet`, `lib-net/src/test/java/rhizome/net/PeerBanListTest.java#portRotationEscalatesToAnAddressWideBan`, `app-node/src/test/java/rhizome/node/RhizomeNodeTest.java#allPeersBannedIsObservedAsAnEclipsedSync` |
| NET-06 | Evade a ban by rotating ports or URL spellings. | A2 | DEFENDED | `lib-net/src/test/java/rhizome/net/PeerBanListTest.java#banIsKeyedByEndpointNotAddress`, `lib-net/src/test/java/rhizome/net/PeerIdentityTest.java#oneIdentityPerCanonicalUrlWhateverTheReceivedSpelling` |
| NET-07 | Get an innocent host banned by sharing its address, or by spraying offences to overflow the ban table. | A2 | DEFENDED | `lib-net/src/test/java/rhizome/net/PeerBanListTest.java#overflowBucketCountsButNeverBansInnocentHosts`, `lib-net/src/test/java/rhizome/net/PeerBanListTest.java#concurrentSprayKeepsTablesBoundedAndActiveBansStick` |
| NET-08 | Capture the node's configured peer bearer token by getting itself gossiped in, or by downgrading to cleartext. | A2 | DEFENDED | `lib-net/src/test/java/rhizome/net/PeerTokenPolicyTest.java#gossipLearnedPeerNeverReceivesTheToken`, `lib-net/src/test/java/rhizome/net/PeerTokenPolicyTest.java#configuredHttpPeerNeverReceivesTheToken` |
| NET-09 | Exhaust memory through unbounded rate-limiter, DNS-cache or gossip-backlog tables. | A2 | DEFENDED | `lib-net/src/test/java/rhizome/net/RateLimiterTest.java#clientTableIsBoundedAndSweepsExpired`, `lib-net/src/test/java/rhizome/net/PeerHostsCacheBoundTest.java#dnsCacheStaysBoundedUnderManyDistinctHosts`, `lib-net/src/test/java/rhizome/net/PeerBroadcasterTest.java#gossipBacklogIsBoundedInBytes` |
| NET-10 | Follow a redirect to reach a target the SSRF filter refused. | A2 | DEFENDED | `lib-net/src/test/java/rhizome/net/PeerRegistrySecurityTest.java#rejectsMalformedSchemes`, `lib-net/src/test/java/rhizome/net/PeerUrlsTest.java#degenerateInputsDegradeWithoutThrowing` |

## API — node HTTP surface

| ID | Scenario | Class | Verdict | Proof |
|----|----------|-------|---------|-------|
| API-01 | Make the operator's browser submit a state-changing POST to their own node. | A5 | DEFENDED | `app-node/src/test/java/rhizome/node/NodeApiTest.java#browserPostIsRefusedUnlessSameOriginWithTheCsrfHeader`, `app-node/src/test/java/rhizome/node/NodeAssemblyTest.java#theHostAllowlistIsComputedAtAssemblyAndCarriesTheAdvertisedName`, `app-node/src/test/java/rhizome/adversarial/e2e/E2EApiAbuseTest.java#aRebindingPostThatLooksSameOriginIsRefusedByTheHostAllowlist` |
| API-02 | Occupy the single event-loop thread with a flood of cheap submissions that each trigger proof-of-work verification. | A1 | DEFENDED | `app-node/src/test/java/rhizome/node/NodeApiTest.java#submitPowGateShedsBlocksBeforeTheBodyIsDecoded`, `app-node/src/test/java/rhizome/node/NodeApiTest.java#aggregateMempoolSignatureGateShedsTransactionsBeforeVerifying` |
| API-03 | Occupy the event loop with read-only VM calls or explorer reads that decode blocks under the consensus lock. | A1 | DEFENDED | `app-node/src/test/java/rhizome/node/NodeApiTest.java#readonlyGasGateShedsCallsOnceTheGlobalBudgetIsSpent`, `app-node/src/test/java/rhizome/node/NodeApiTest.java#aggregateReadGateShedsExplorerReadsPastTheGlobalBudget` |
| API-04 | Stall sockets to hold connections open without sending or draining bytes. | A1 | DEFENDED | `lib-net/src/test/java/rhizome/net/BodyReadDeadlineTest.java#idleDeadlineKillsAStalledExchange`, `lib-net/src/test/java/rhizome/net/BodyReadDeadlineTest.java#saturatedPoolRejectsInsteadOfRunningInline`, `app-node/src/test/java/rhizome/adversarial/e2e/E2EApiAbuseTest.java#aStalledClientDoesNotHoldTheNodeHostage` |
| API-05 | Reach a token-guarded route through an alternative path spelling — trailing slash, percent-encoding, dot segments. | A1 | DEFENDED | `app-node/src/test/java/rhizome/node/RoutePathNormalizationTest.java#everySpellingThatReachesTheIngestHandlerIsBearerGated`, `app-node/src/test/java/rhizome/node/RoutePolicyCompletenessTest.java#everyRegisteredRouteIsClassified` |
| API-06 | Bypass the per-request cost accounting by choosing a route spelling the cost table does not recognise. | A1 | DEFENDED | `app-node/src/test/java/rhizome/node/RoutePathNormalizationTest.java#everySpellingThatReachesTheIngestHandlerCarriesTheSubmitCost`, `app-node/src/test/java/rhizome/node/RoutePathNormalizationTest.java#readCostsSurviveTrailingSlashesAndPercentEncoding` |
| API-07 | Crash or wedge a handler with malformed input, out-of-range indexes, or oversized bodies. | A1 | DEFENDED | `app-node/src/test/java/rhizome/node/NodeApiTest.java#badInputAlwaysGets400NeverCrashes`, `app-node/src/test/java/rhizome/node/NodeApiTest.java#outOfIntRangeIndexesAreRejectedBeforeTheCast`, `app-node/src/test/java/rhizome/node/NodeApiTest.java#oversizedBodyIsRejectedNotBuffered` |
| API-08 | Open a node to the world with no API token and no explicit opt-in. | A0 | DEFENDED | `app-node/src/test/java/rhizome/node/NodeAssemblyTest.java#anOpenBindWithoutATokenIsRefusedBeforeAnythingIsOpened` |
| API-09 | Spoof a client address through `X-Forwarded-For` to escape per-IP rate limiting. | A1 | DEFENDED | `app-node/src/test/java/rhizome/node/NodeApiHardeningFlagsTest.java#overflowingOctetsAreRejectedSoGetByNameCanNeverDnsResolveThem`, `app-node/src/test/java/rhizome/node/NodeApiTest.java#rateLimitReturns429OverTheLimit` |
| API-10 | Read a served file outside the bundled asset set. | A1 | DEFENDED | `app-node/src/test/java/rhizome/node/DocsAssetsTest.java#lookupsOutsideTheManifestMiss` |
| API-11 | Exhaust the log-stream subscriber table to deny the dashboard to the operator. | A1 | DEFENDED | `app-node/src/test/java/rhizome/node/SseLogStreamTest.java#subscriberCapReturns503AndClosedSubscribersArePruned` |
| API-12 | Push junk blocks repeatedly to keep the node verifying, without ever earning a ban. | A2 | DEFENDED | `app-node/src/test/java/rhizome/node/NodeApiTest.java#pushAbuseAccumulatesStrikesAndGetsShedEarly`, `app-node/src/test/java/rhizome/node/PushStrikeTableTest.java#onlyProvableJunkCounts` |

## CODEC — wire decoding bounds

| ID | Scenario | Class | Verdict | Proof |
|----|----------|-------|---------|-------|
| CODEC-01 | Declare a transaction or uncle count far beyond the bytes present, to force a giant allocation. | A2 | DEFENDED | `lib-core/src/test/java/rhizome/CodecBoundsTest.java#rejectsHugeTransactionCount`, `lib-core/src/test/java/rhizome/CodecBoundsTest.java#rejectsHugeUncleCount`, `lib-core/src/test/java/rhizome/CodecBoundsTest.java#blockDtoRejectsHugeTransactionCount` |
| CODEC-02 | Smuggle trailing bytes past a decoder so two wire forms map to one object — a latent malleability source. | A2 | DEFENDED | `lib-core/src/test/java/rhizome/CodecBoundsTest.java#rejectsTrailingBytesAfterHeader`, `lib-core/src/test/java/rhizome/BinaryCodecTest.java#strictSingleObjectDecodeRejectsTrailingBytes` |
| CODEC-03 | Exploit a decoder that silently drops a hash-committed optional field, so the two wire forms hash differently — a latent split. | A2 | DEFENDED | `lib-core/src/test/java/rhizome/JsonWriterEquivalenceTest.java#blockMatrixHashRoundTrips`, `lib-core/src/test/java/rhizome/BlockCodecStreamTest.java#preservesNonZeroVoteThroughBinaryRoundTrip` |
| CODEC-04 | Reach a stricter path through a laxer one — JSON accepting what the binary decoder refuses. | A2 | DEFENDED | `lib-core/src/test/java/rhizome/CodecBoundsTest.java#blockFromJsonEnforcesBinaryCodecBounds`, `lib-core/src/test/java/rhizome/CodecBoundsTest.java#transactionFromJsonEnforcesPayloadCap` |
| CODEC-05 | Feed an unknown or reserved enum code (transaction kind, signature scheme) to reach an unintended branch. | A2 | DEFENDED | `lib-core/src/test/java/rhizome/transaction/TransactionKindWireTest.java#unknownCodesAreRejected`, `lib-crypto/src/test/java/rhizome/crypto/SignatureSchemeTest.java#reservedAndUnknownCodesAreRejected` |
| CODEC-06 | Break out of a JSON string with control characters, lone surrogates or invalid UTF-8 to corrupt a served document. | A1 | DEFENDED | `lib-core/src/test/java/rhizome/JsonSinkEscapingTest.java#loneSurrogatesEmbeddedMidString`, `lib-core/src/test/java/rhizome/JsonSinkEscapingTest.java#scriptTagSlashCombinationsAndFourCharAlphabetProduct` |
| CODEC-07 | Abort a streamed decode mid-object, or exceed the streamed block cap. | A2 | DEFENDED | `lib-core/src/test/java/rhizome/BlockCodecStreamTest.java#abortsOnAStreamThatEndsMidBlock`, `lib-core/src/test/java/rhizome/BlockCodecStreamTest.java#abortsWhenTheStreamExceedsMaxBlocks` |

## WALLET — client key handling

| ID | Scenario | Class | Verdict | Proof |
|----|----------|-------|---------|-------|
| WALLET-01 | Read a key file that was written unencrypted, or as world-readable. | A6 | DEFENDED | `app-wallet/src/test/java/rhizome/wallet/WalletTest.java#plaintextSaveIsRefusedNonInteractivelyWithoutOptIn`, `app-wallet/src/test/java/rhizome/wallet/WalletTest.java#keyFileIsOwnerOnlyOnPosix` |
| WALLET-02 | Tamper with an encrypted key file, or downgrade its KDF parameters, so it decrypts to attacker-chosen material. | A6 | DEFENDED | `app-wallet/src/test/java/rhizome/wallet/WalletKeystoreTest.java#tamperedScryptParametersAreRejected`, `app-wallet/src/test/java/rhizome/wallet/WalletKeystoreTest.java#unknownKdfIsRejected`, `app-wallet/src/test/java/rhizome/wallet/WalletTest.java#tamperedEncryptedKeyFileIsDetectedOnLoadAndOnPinWrite` |
| WALLET-03 | Present a plaintext file as an envelope (or the reverse) so the wallet fails open. | A6 | DEFENDED | `app-wallet/src/test/java/rhizome/wallet/WalletKeystoreTest.java#plaintextIsNotMistakenForEnvelope`, `app-wallet/src/test/java/rhizome/wallet/WalletKeystoreTest.java#spoofedMarkerWithoutPayloadFieldsIsTreatedAsPlaintext` |
| WALLET-04 | Get a wallet to sign for a different network by swapping the node it talks to. | A2 | DEFENDED | `app-wallet/src/test/java/rhizome/wallet/WalletCliTest.java#pinnedMismatchIsRefused`, `app-wallet/src/test/java/rhizome/wallet/WalletCliTest.java#firstContactPinsTheNodeChainId` |
| WALLET-05 | Overflow the fee product, or slip a malformed amount, identifier or gas parameter past client validation. | A1 | DEFENDED | `app-wallet/src/test/java/rhizome/wallet/WalletCliArgumentParsingTest.java#callRefusesAGasPriceThatWouldOverflowTheFeeProduct`, `app-wallet/src/test/java/rhizome/wallet/WalletCliArgumentParsingTest.java#sendRefusesAnAmountFinerThanOneBaseUnit` |
| WALLET-06 | Inject JSON metacharacters through a node URL to corrupt the key file. | A5 | DEFENDED | `app-wallet/src/test/java/rhizome/wallet/WalletTest.java#nodeUrlWithJsonMetacharactersDoesNotCorruptTheKeyFile` |
| WALLET-07 | Make the browser wallet and the node disagree on a derivation, so a signed transaction spends from an address the user never saw. | A5 | DEFENDED | `app-node/src/test/java/rhizome/node/BrowserWalletVectorTest.java#browserSignedTransferParsesAndVerifies`, `app-node/src/test/java/rhizome/node/BrowserWalletVectorTest.java#contractAddressDerivationMatchesJs` |


## E2E — the assembled node and network

Scenarios whose subject is the system rather than a rule: real nodes, real sockets, real proof of
work, real RocksDB. Fixtures live in `app-node/src/test/java/rhizome/adversarial/e2e/`
(`TestNetwork`, `E2EFixtures`, `HostilePeer`, `RawHttp`).

| ID | Scenario | Class | Verdict | Proof |
|----|----------|-------|---------|-------|
| E2E-01 | A node that mined its own branch in isolation keeps it, refusing the heavier history its peers hold. | A3 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EForkConvergenceTest.java#aNodeOnALighterBranchAdoptsTheHeavierOneItLearnsOverHttp` |
| E2E-02 | Two camps that mined through a partition stay split after it heals. | A3 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EForkConvergenceTest.java#twoIsolatedMiningNodesThatMeetAgreeOnHistory` |
| E2E-03 | Spend the same coins on two branches and keep both payments once the branches meet. | A3 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EDoubleSpendTest.java#conflictingSpendsOnTwoBranchesResolveToExactlyOnePayment` |
| E2E-04 | Censor a payment permanently by getting it mined into a branch and then orphaning that branch. | A3 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EDoubleSpendTest.java#aPaymentUndoneByAReorgCanBeMinedAgainOnTheWinningBranchAndPaysOnce` |
| E2E-05 | Reach a state-changing route on a token-protected node without the bearer. | A1 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EApiAbuseTest.java#stateChangingRoutesAreRefusedWithoutTheBearerAndTheNodeKeepsProducing` |
| E2E-06 | Make the operator's browser POST to their own node from an attacker's page. | A5 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EApiAbuseTest.java#aCrossOriginBrowserPostIsRefused` |
| E2E-07 | Defeat the same-origin check by rebinding DNS so `Origin` and `Host` agree. | A5 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EApiAbuseTest.java#aRebindingPostThatLooksSameOriginIsRefusedByTheHostAllowlist` |
| E2E-08 | Drive lock-guarded block decodes from one address until production is starved. | A1 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EApiAbuseTest.java#anExpensiveReadFloodIsShedWith429AndTheNodeKeepsProducing` |
| E2E-09 | Crash a handler with malformed input across the whole surface. | A1 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EApiAbuseTest.java#malformedInputIsAlwaysAnswereWithAStatusAndNeverKillsTheNode` |
| E2E-10 | Hold the event loop with a client that announces a body and never sends it. | A1 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EApiAbuseTest.java#aStalledClientDoesNotHoldTheNodeHostage` |
| E2E-11 | Claim an enormous chain over the wire and serve a branch that paid for nothing. | A2 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EHostilePeerTest.java#aPeerClaimingHugeWorkAndProvingNoneChangesNothing` |
| E2E-12 | Serve a branch the decoder cannot parse, mid-sync, to a node that is also mining. | A2 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EHostilePeerTest.java#aPeerServingAnUndecodableBranchIsContainedInsideTheSyncPass` |
| E2E-13 | Answer a scalar endpoint with megabytes, to be parsed before it is bounded. | A2 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EHostilePeerTest.java#aPeerAnsweringWithAnEnormousScalarIsRejectedNotParsed` |
| E2E-14 | Get an honest-but-broken peer banned, so failing to answer becomes an eclipse primitive. | A2 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EHostilePeerTest.java#aPeerFailingEveryRequestIsNotTreatedAsMisbehaviour` |
| E2E-15 | Trickle a response forever so every byte is progress and no idle timeout fires. | A2 | BOUNDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EHostilePeerTest.java#aPeerThatDripsForeverParksOnlyItsOwnSyncRoundAndNeverProduction` |
| E2E-16 | Flood a node with valid signed transactions until it stops producing blocks. | A1 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2ENodeResilienceTest.java#aFloodOfSignedTransactionsOverHttpNeverStopsBlockProduction` |
| E2E-17 | Restart a node and have it come back short, or with replayable nonces. | A6 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2ENodeResilienceTest.java#aRestartOnTheSameDataDirectoryRestoresChainBalancesAndNonces` |
| E2E-18 | Eclipse a node silently, so it syncs from nobody and nothing says so. | A2 | DEFENDED | `app-node/src/test/java/rhizome/node/RhizomeNodeTest.java#allPeersBannedIsObservedAsAnEclipsedSync`, `app-node/src/test/java/rhizome/node/RhizomeNodeTest.java#noPeerAtAllIsAlsoAnEclipse` |
| E2E-19 | Lock two forked camps into a mutual ban so the natural heal can never happen. | A3 | DEFENDED | `app-node/src/test/java/rhizome/node/RhizomeNodeTest.java#aDeepForkedPeerIsRefusedButNeverBanned` |
| E2E-20 | Ban an honest node by proxy: enqueue its address as a peer and let it serve non-protocol replies. | A1 | DEFENDED | `app-node/src/test/java/rhizome/node/RhizomeNodeTest.java#aHostThatNeverSpokeTheProtocolIsDroppedNotBanned` |
| E2E-21 | Withhold blocks from peers by never gossiping what is mined. | A3 | DEFENDED | `app-node/src/test/java/rhizome/node/GossipPropagationTest.java#minerPushesBlocksToPeer` |
| E2E-22 | Have a node advertise its loopback peers, leaking an internal topology into discovery. | A2 | DEFENDED | `app-node/src/test/java/rhizome/node/PeerDiscoveryTest.java#loopbackPeersAreNotDiscoveredByDefault` |
| E2E-23 | Break the wallet against a real node, so a signed payment is accepted locally and refused on chain. | A1 | DEFENDED | `app-wallet/src/test/java/rhizome/wallet/WalletNodeIntegrationTest.java#fundedWalletSendsCoinsThroughRunningNode` |
| E2E-24 | Bootstrap a fresh node from a hostile or unburied snapshot pivot. | A2 | DEFENDED | `app-node/src/test/java/rhizome/node/SnapSyncIntegrationTest.java#freshNodeBootstrapsFromSnapshotAndConvergesWithoutHistoricalBodies`, `app-node/src/test/java/rhizome/node/SnapSyncIntegrationTest.java#bootstrapRefusesAnUnburiedPivot` |
| E2E-25 | Serve a truncated or reorging view mid-sync so a fresh node adopts a partial chain. | A2 | DEFENDED | `app-node/src/test/java/rhizome/node/NodeSyncIntegrationTest.java#freshNodeSyncsFromHttpPeer`, `app-node/src/test/java/rhizome/node/NodeSyncIntegrationTest.java#aReorgingPeerServes503AndReadsAsUnavailableNotInvalid` |
| E2E-26 | Break contract execution on the wire, so a deploy or a call is accepted by the API and never mined or paid for. | A1 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EContractTest.java#aContractIsDeployedAndCalledThroughTheHttpSurface` |
| E2E-27 | Push a "poison block" at `/submit`: a free contract call declaring more gas than the ceiling, to stall every validating node under its consensus lock. | A3 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EContractTest.java#aPoisonBlockPushedAtTheSubmitRouteIsRefusedAndTheNodeStaysHealthy` |
| E2E-28 | Sit in two honest nodes' peer sets at once and poison the fork choice between them. | A2 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EHostilePeerTest.java#twoHonestNodesConvergeWithEachOtherDespiteALiarInBothPeerSets` |

---

## Known gaps

Scenarios the protocol names but does not yet prove. Each is here because closing it needs work
beyond writing a test — a fixture that does not exist, or a decision that has not been taken.

| ID | Scenario | Why it is open |
|----|----------|----------------|
| REORG-11 | Selfish mining: withhold blocks and release them to orphan honest work, measured as a revenue advantage rather than a validity failure. | The multi-node harness now exists (`TestNetwork`, used by E2E-01/02), so the missing piece is narrower than it was: a hash-rate model and a revenue metric. Every block in the attack is valid, so there is no rejection to assert — the question is economic, and the depth bound (REORG-02) already caps the damage. |
| NET-11 | Partition a node by exploiting the interaction between ban scoring and the discovery round over long periods. | Needs a time-accelerated fixture: the mechanisms are pinned individually (NET-05..NET-07) and a single heal is pinned end to end (E2E-02), but their composition over hours cannot be observed in a suite that must finish in a minute. |
| API-13 | Timing side channels on the API token comparison. | No constant-time comparison audit has been done; the token is a bearer secret over a loopback-or-proxy interface, so the exposure is low, but the claim is untested. |

## Change log

- **2026-08-19** — Network layer added. A new `E2E` family of 28 scenarios covering the assembled
  system: fork convergence and partition heal between real mining nodes, a cross-branch double
  spend and its reorg-replay mirror on a shared premined genesis, HTTP abuse written on raw sockets
  (bearer, CSRF, DNS rebinding, read floods, malformed input, slow loris), a hostile peer serving
  unproven and undecodable branches over a real socket, a signed-transaction flood, and a restart
  on a live data directory, a contract deployed and called over HTTP, a poison block pushed at
  `/submit`, and a liar sitting in two honest nodes' peer sets while they converge with each other.
  Twenty new proofs under `rhizome.adversarial.e2e`, plus eight
  pre-existing full-node tests that the catalogue had never cited — `RhizomeNodeTest`,
  `GossipPropagationTest`, `PeerDiscoveryTest`, `SnapSyncIntegrationTest`,
  `WalletNodeIntegrationTest`, `NodeSyncIntegrationTest`. Eight component scenarios gained a
  network-level proof alongside their existing one. A **Proof levels** section now states which
  layer a scenario rests on, because "133 DEFENDED" read as if it were uniform and was not.
  The gate was extended to scan every module's `rhizome.adversarial` package rather than
  `lib-core`'s alone — and to accept family prefixes containing digits, which had silently made
  the whole `E2E` family invisible to it.
- **2026-08-18** — Protocol established. 17 families, 143 catalogued scenarios — 133 `DEFENDED`,
  4 `BOUNDED`, 3 `RESIDUAL`, 3 `GAP` — resting on 247 proof references into 89 test files.
  Fixtures (`AdversarialChain`, `BlockForge`, `AdversarialPeer`) and six attack suites added under
  `rhizome.adversarial`, closing the component vectors that had no direct proof: the CVE-2012-2459 body swap,
  the Ed25519 malleability double-spend, reorg replay, the finality-window boundary on both sides,
  the multi-window timestamp campaign, and supply conservation. Enforced by
  `AdversarialProtocolTest`, which runs as its own Gradle task (`:lib-core:adversarialProtocolCheck`,
  wired into `check`) declaring the catalogue and every cited test source as inputs — without that,
  editing the catalogue alone left the task up to date and the build green over a broken link.
