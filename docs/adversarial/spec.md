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

The distribution is deliberate and worth stating, because "173 DEFENDED" reads as if it were
uniform: of the 198 catalogued scenarios, **135** rest at component level, **14** at the surface,
**46** reach the network, and 3 are residuals with no proof by definition. The network figure is the `E2E`
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
`SUPPLY` circulating supply header commitment ·
`GENESIS` pinned genesis supply and allocation ·
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

## SUPPLY — circulating supply header commitment

The optional eleventh header field committing each block's circulating supply: `block.supply ==
parent.supply + Issuance.minted(...)`, prefix-closed across a chain's history — a chain commits
supply at every height from genesis, or at none — and checked by one `checkSupply` formula shared
by both consensus gates that see a header, `ChainEngine.addBlock` and header-only sync's
`HeaderChain.validate`. Most proofs live in `lib-core/src/test/java/rhizome/SupplyCommitmentTest.java`,
alongside the genesis, codec and header-sync suites that already covered the seeding, wire and
bootstrap boundaries; the two gaps those left — the accounting identity's own arithmetic overflowing,
and a pre-feature-shaped header blob surviving truncated — are closed by
`lib-core/src/test/java/rhizome/adversarial/SupplyLedgerAttackTest.java`.

| ID | Scenario | Class | Verdict | Proof |
|----|----------|-------|---------|-------|
| SUPPLY-01 | Forge a block that commits supply one base unit above or below the exact accounting identity, to slip a phantom mint or a silent burn past a node that only checks the coinbase. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/SupplyCommitmentTest.java#supplyCommitmentMatchesScheduledIssuanceExactly` |
| SUPPLY-02 | Graft a supply commitment onto a block whose parent chain never carried one, or drop the commitment beneath a parent that did, laundering a discontinuous supply history into an otherwise well-formed chain. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/SupplyCommitmentTest.java#supplyCommitmentIsPrefixClosed` |
| SUPPLY-03 | Under-report a block's committed supply by omitting the work-scaled share of an uncle or nephew reward, hiding real issuance behind an otherwise-exact coinbase. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/SupplyCommitmentTest.java#supplyAccountingIncludesWorkScaledUncleAndNephewIssuance` |
| SUPPLY-04 | Push the accounting identity's sum past the signed 64-bit range so it wraps instead of failing, escaping the exact-match check through integer overflow. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/adversarial/SupplyLedgerAttackTest.java#anOverflowingSupplySumIsRejectedRatherThanWrappedIntoAFalseMatch` |
| SUPPLY-05 | Seed a genesis snapshot whose unsigned balance sum exceeds the signed 64-bit range, so every downstream supply figure is undefined or silently wrapped from block zero. | A6 | DEFENDED | `lib-core/src/test/java/rhizome/GenesisBlockTest.java#genesisCommitsSnapshotTotalSupply` |
| SUPPLY-06 | Submit a supply value below the absent sentinel at the wire boundary, hoping a malformed decode is silently accepted as legitimate absence, or crashes the decoder, before consensus arithmetic ever sees it. | A2 | DEFENDED | `lib-core/src/test/java/rhizome/CodecBoundsTest.java#everyDecoderRejectsOutOfRangeSupply` |
| SUPPLY-07 | Feed the current decoder a pre-feature-shaped header blob, eight bytes short of the field it now expects, hoping the truncated legacy shape is silently misread as a valid supply-less header instead of blocking boot. | A6 | DEFENDED | `lib-core/src/test/java/rhizome/adversarial/SupplyLedgerAttackTest.java#aHeaderTruncatedRightBeforeTheSupplyFieldIsRejectedOnEveryDecoder` |
| SUPPLY-08 | Pop back through a reorg and look for any supply figure that needs rollback arithmetic to come out right, instead of being the popped-to header's committed value by construction. | A3 | DEFENDED | `lib-core/src/test/java/rhizome/SupplyCommitmentTest.java#reorgRestoresCommittedSupplyStructurally` |
| SUPPLY-09 | Abandon a reorg mid-apply so the victim falls back to its trusted-restore path, hoping the restored suffix's supply is skipped or re-validated to the wrong value rather than its exact pre-pop figure. | A2 | DEFENDED | `lib-core/src/test/java/rhizome/SupplyCommitmentTest.java#failedReorgRestoreRevalidatesIdenticalSupply` |
| SUPPLY-10 | Forge a header chain's per-height supply delta so a headers-first sync client accepts a later header's proof of work before ever checking the emission chain beneath it. | A2 | DEFENDED | `lib-core/src/test/java/rhizome/HeaderChainTest.java#headerGateRejectsForgedSupplyBeforeProofOfWork` |
| SUPPLY-11 | Get a syncing node to download even one block body from a branch whose emission chain is already forged at the header level, turning a free header lie into a paid-for body fetch. | A2 | DEFENDED | `lib-core/src/test/java/rhizome/HeaderSynchronizerTest.java#peerServingAForgedEmissionHeaderIsRejectedBeforeAnyBodyFetch` |

## GENESIS — pinned genesis supply and allocation

The per-network genesis supply `S₀` (`NetworkParameters.genesisSupply`, unpinned sentinel
`GENESIS_SUPPLY_UNPINNED`) checked exactly against the loaded snapshot's total in
`GenesisBlock.build`, on every boot path, before any balance is seeded. The pin guards the
*total*; the existing genesis commitment (`SHA-256(chainId ‖ snapshotCommitment)`) separately
guards the *distribution* — the two checks compose rather than duplicate each other. Proofs
live alongside the existing genesis and snapshot suites
(`lib-core/src/test/java/rhizome/GenesisBlockTest.java`,
`lib-core/src/test/java/rhizome/LedgerSnapshotTest.java`,
`lib-core/src/test/java/rhizome/ChainEngineTest.java`) rather than in a new attack suite: the
check is a boot-time equality composed with guards those suites already exercise.

| ID | Scenario | Class | Verdict | Proof |
|----|----------|-------|---------|-------|
| GENESIS-01 | Boot a pinned network from a snapshot whose total differs from `S₀` by one base unit — misconfiguration or a swapped/served file — hoping the divergence is accepted and only surfaces later as an opaque genesis-hash mismatch or a silent fork. | A6 | DEFENDED | `lib-core/src/test/java/rhizome/GenesisBlockTest.java#aSnapshotWhoseTotalDiffersFromThePinnedGenesisSupplyRefusesBoot` |
| GENESIS-02 | Keep the snapshot's total equal to the pin but change its distribution between restarts, hoping the pinned-total check alone is mistaken for full genesis integrity and the commitment re-verification is skipped or ordered after it. | A6 | DEFENDED | `lib-core/src/test/java/rhizome/GenesisBlockTest.java#thePinChecksTheTotalAndTheCommitmentBindsTheDistribution` |
| GENESIS-03 | Edit the shipped mainnet allocation artifact without updating the pinned constant (or vice versa), shipping a network definition whose own default genesis input disagrees with its own consensus constant. | A6 | DEFENDED | `lib-core/src/test/java/rhizome/LedgerSnapshotTest.java#theShippedAllocationMatchesThePinnedGenesisSupplyExactly` |

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
| REORG-11 | Selfish mining: withhold blocks and release them selectively to earn a revenue share above the miner's hash-rate share. | A3 | BOUNDED | `lib-core/src/test/java/rhizome/adversarial/SelfishMiningAttackTest.java#onlyTheFirstBlockOfAnOrphanedBranchIsEverRefundedAsAnUncle`, `lib-core/src/test/java/rhizome/adversarial/SelfishMiningAttackTest.java#aBranchWithheldPastTheFinalityWindowEarnsNothingAtAll`, `lib-core/src/test/java/rhizome/adversarial/SelfishMiningAttackTest.java#aMinerThatPublishesEveryBlockEarnsExactlyItsHashRateShare`, `lib-core/src/test/java/rhizome/adversarial/SelfishMiningAttackTest.java#aRunIsAPureFunctionOfItsSeedSoTheMeasurementIsReproducible`, `lib-core/src/test/java/rhizome/adversarial/SelfishMiningAttackTest.java#withholdingBlocksEarnsAFortyPercentMinerMoreThanItsHashRateShare`, `lib-core/src/test/java/rhizome/adversarial/SelfishMiningAttackTest.java#withholdingBlocksCostsATenPercentMinerMoreThanItEarns`, `lib-core/src/test/java/rhizome/adversarial/SelfishMiningAttackTest.java#ghostUncleRewardsShrinkTheSelfishMinersEdgeWithoutClosingIt` |
| REORG-12 | Grind the deterministic tip-hash tie-break by re-mining a contested block in search of a smaller hash. | A3 | BOUNDED | `lib-core/src/test/java/rhizome/adversarial/SelfishMiningAttackTest.java#grindingTheTieBreakRequiresAFullProofOfWorkSolvePerAttemptAgainstAFairCoin` |

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
| VM-01 | Deploy a module using floating-point or vector-float opcodes, whose results differ per JVM, to fork the chain. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#rejectsAModuleUsingAFloatOpcode`, `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#rejectsAnF32ConstInAGlobalInitExpression`, `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#rejectsAnF64ConstInAGlobalInitExpression` |
| VM-02 | Use WASM GC opcodes to allocate on the JVM heap outside every gas and memory budget. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#rejectsAGcArrayTypeInTheTypeSection`, `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#rejectsArrayNewDefaultInAFunctionBody`, `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#rejectsArrayNewDefaultInAGlobalInitExpression` |
| VM-03 | Blow up the parser before gas metering can apply, with declared counts far larger than the bytes present. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/WasmPreScanCountsTest.java#rejectsFunctypeWhoseParamCountExceedsTheBytesPresent`, `lib-vm/src/test/java/rhizome/vm/WasmPreScanCountsTest.java#rejectsDataSegmentDeclaringHugePayload`, `lib-vm/src/test/java/rhizome/vm/WasmPreScanCountsTest.java#rejectsElementSegmentDeclaringHugeInitializerCount` |
| VM-04 | Exhaust one node's heap with locals×depth recursion so it OOMs while another reverts — a heap-dependent fork. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/WasmLocalsGuardTest.java#localsHeavyRecursionTrapsOnTheDeterministicLocalsBudget`, `lib-vm/src/test/java/rhizome/vm/WasmLocalsGuardTest.java#localsBudgetRevertIsDeterministicWarmAndCold`, `lib-vm/src/test/java/rhizome/vm/WasmDepthLimitTest.java#deepRecursionRevertsDeterministicallyInsteadOfCrashing` |
| VM-05 | Make `gasUsed` depend on whether the module cache was warm, so nodes disagree on the fee and the state root. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/WasmVmTest.java#moduleParseGasIsChargedIdenticallyOnWarmAndColdCache` |
| VM-06 | Grow tables or memory past the metered caps, unmetered. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#rejectsATableWhoseUnboundedMaxCouldGrowUnmetered`, `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#rejectsATableDeclaringAnOversizedExplicitMax`, `lib-vm/src/test/java/rhizome/vm/WasmVmTest.java#rejectsModuleWhoseTablesAggregateOverTheCap`, `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#rejectsMemoryDeclaringTooManyInitialPages` |
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
| VM-17 | Buy an early-failing or gas-starved CALL for free, at any gasPrice including 0. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#aCallToAnUnknownContractStillPaysTheIntrinsicGas`, `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#aCallBelowTheIntrinsicGasLimitPaysItsWholeLimit` |
| VM-18 | Declare a function type with an oversized parameter list to force an unmetered per-frame locals allocation Chicory enforces no cap of its own on. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#rejectsAFunctionTypeDeclaringTooManyParams`, `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#acceptsAFunctionTypeAtTheParamsCap` |
| VM-19 | Declare an unbounded count of globals, functions, imports or exports, each materialised or instantiated before any gas is charged. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#rejectsAModuleDeclaringTooManyGlobals`, `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#rejectsAModuleDeclaringTooManyFunctions`, `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#rejectsAModuleDeclaringTooManyImports`, `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#rejectsAModuleDeclaringTooManyExports` |
| VM-20 | Make a failed `memory.grow` at the instance cap consume pages against the tree-wide budget it never allocated, so a later legitimate grow reverts for memory that never existed. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#aFailedGrowDoesNotConsumeTheTreePageBudget` |
| VM-21 | Deploy a module missing the `call` export, or importing memory/table/global under a whitelisted host-function name, to install dead code or escape the bounded memory factory. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#rejectsAModuleWithoutACallExport`, `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#acceptsAModuleUsingOnlyTheWhitelistedAbi` |
| VM-22 | Force a host buffer read whose allocation size tracks local heap pressure rather than a deterministic cap, so nodes with different heaps could disagree. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#aHostBufferAtTheCapIsAllowed`, `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#aHostBufferAboveTheCapIsFullGasOutOfGas`, `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#theCapPathIsDeterministicRegardlessOfHeapPressure` |
| VM-23 | Read a box's contents for less than its full serialized-size gas charge, by having the charge land after `serialize()` runs instead of before. | A1 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/WasmAdversarialTest.java#boxReadChargesTheFullSerializedSizeBeforeSerializing` |

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
| PERS-01 | Leave contract, box or token state behind after a reorg, so a rewritten block leaves residue. | A3 | DEFENDED | `lib-vm/src/test/java/rhizome/vm/ContractConsensusTest.java#popRevertsContractStateExactly`, `lib-core/src/test/java/rhizome/BoxConsensusTest.java#popRevertsBoxStateExactly`, `lib-core/src/test/java/rhizome/TokenConsensusTest.java#mintTransferBurnThenPop`, `app-node/src/test/java/rhizome/adversarial/e2e/E2EStateDomainsTest.java#aReorgReversesBoxAndTokenStateExactlyOnARealNode` |
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
| NET-11 | Partition a node permanently by composing ban scoring and PEX eviction over a long horizon — hours to days, not one round or one strike. | A2 | BOUNDED | `lib-net/src/test/java/rhizome/adversarial/BanDiscoveryPartitionAttackTest.java#sixteenSybilsSaturatingTheLoopbackSubnetLockOutAnHonestPeerForTheWholeHorizon`, `lib-net/src/test/java/rhizome/adversarial/BanDiscoveryPartitionAttackTest.java#aSeedSurvivesFortyEightHoursOfMisbehavingSybilsFillingTheTable`, `lib-net/src/test/java/rhizome/adversarial/BanDiscoveryPartitionAttackTest.java#fiveScatteredFailuresNeverEvictButThreeConsecutiveDo`, `lib-net/src/test/java/rhizome/adversarial/BanDiscoveryPartitionAttackTest.java#anEvictedPeerRejoinsExactlyWhenTheRemovalCooldownExpires`, `lib-net/src/test/java/rhizome/adversarial/BanDiscoveryPartitionAttackTest.java#aBanHoldsForExactlyItsWindowAndDoesNotRenewOnASingleLateStrike` |

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
| API-13 | Ship a bearer comparison that leaks timing information through short-circuiting equality or a length-only check, so a shared prefix of the token can be recovered by repeated probing. | A1 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/TokenComparisonAttackTest.java#bearerComparisonStaysConstantTimeAndIsTheOnlyPlaceTheTokenIsCompared`, `app-node/src/test/java/rhizome/adversarial/e2e/E2EApiAbuseTest.java#everyStrictPrefixOfTheBearerIsRefusedAndOnlyTheFullTokenPasses` |

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
| E2E-29 | Create a box or mint a token whose state the node commits but does not serve back, so clients and consensus disagree. | A1 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EStateDomainsTest.java#aBoxAndATokenCreatedOnARealNodeAreCommittedAndServedBack` |
| E2E-30 | Leave box or token state behind after the branch that created it is orphaned — a partial revert, which is a permanent state-root fork rather than a lost box. | A3 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EStateDomainsTest.java#aReorgReversesBoxAndTokenStateExactlyOnARealNode` |
| E2E-31 | Have a pruned node answer for history it no longer holds — a truncated view served as if it were the chain, rather than a refusal carrying the watermark. | A2 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EPrunedAndSnapSyncTest.java#aPrunedNodeRefusesPrunedBodiesWithItsWatermarkAndKeepsServingHeaders` |
| E2E-32 | Have a snap-syncing node adopt state it cannot then serve or extend, so a bootstrap ends in silent disagreement with its source. | A2 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EPrunedAndSnapSyncTest.java#aFreshNodeBootstrapsFromAPeersSnapshotAndAgreesWithIt` |
| E2E-33 | Slip a bearer past the gate with a shared prefix or a length shortcut, on a real socket and header parser. | A1 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EApiAbuseTest.java#everyStrictPrefixOfTheBearerIsRefusedAndOnlyTheFullTokenPasses` |
| E2E-34 | Push a supply-forged block straight at a real node's `/submit` route, hoping the API boundary, the real consensus engine and the gossip fault table disagree about whether it is an accepted mutation or a rejected structural fault. | A3 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2ESupplyCommitmentTest.java#aSupplyForgedBlockPushedAtTheSubmitRouteIsRejectedAndTheNodeStaysHealthy` |
| E2E-35 | Serve a real syncing node a headers-only response whose supply delta is forged partway through, over a real socket, hoping the lie survives real parsing, real caps and real deadlines long enough to cost the victim a single body fetch or a byte of local state. | A2 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2ESupplyCommitmentTest.java#aHostileHeadersResponseWithAForgedSupplyDeltaLeavesTheVictimsChainUntouched` |
| E2E-36 | Fork two real mining nodes with divergent uncle inclusion so their per-block issuance genuinely diverges, let them reorg to the heavier branch over real HTTP sync, and see whether the two nodes' real, independently-read supply figures agree once they converge. | A3 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2ESupplyCommitmentTest.java#twoForkedMiningNodesThatConvergeAgreeOnSupplyAtTheSettledHeight` |
| E2E-37 | Boot a real mainnet node with no `RHIZOME_SNAPSHOT` set at all, hoping the classpath-resource fallback is skipped or silently yields an empty ledger instead of the pinned allocation. | A0 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EGenesisIdentityTest.java#aRealMainnetNodeWithNoConfiguredSnapshotLoadsTheEmbeddedAllocationAndCommitsThePinnedSupply` |
| E2E-38 | Boot three independent, real mainnet nodes with no shared file and no peering yet, hoping their genesis blocks disagree by even one bit before gossip gets a chance to paper over it. | A0 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EGenesisIdentityTest.java#threeIndependentMainnetNodesDeriveABitIdenticalGenesisBeforeAnyPeeringHappens` |
| E2E-39 | Serve a victim a hostile peer's real genesis for a different, equal-total but differently-distributed chain while claiming a thousandfold height and work advantage, hoping cumulative work is compared before genesis identity. | A2 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EGenesisEclipseTest.java#aHostileGenesisWithMatchingTotalButADifferentDistributionNeverDisplacesTheVictims` |
| E2E-40 | Eclipse a freshly-joining, snap-syncing node with a single hostile peer claiming a million-block chain and serving headers rooted in nothing real, hoping the node either crashes or borrows the attacker's chain identity. | A2 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EGenesisEclipseTest.java#aFreshlyJoiningSnapSyncingNodeEclipsedByOneHostilePeerNeverCrashesAndKeepsItsOwnGenesis` |
| E2E-41 | Join a pruned node to the network purely through snap-sync against an honest archive peer, hoping the bootstrapped node ends up trusting that peer -- rather than its own locally-built genesis -- for chain identity. | A0 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EGenesisIdentityTest.java#aPrunedNodeJoiningViaSnapSyncDerivesItsGenesisLocallyNotFromItsSyncSource` |
| E2E-42 | Boot an unmodified `testnet()` node from a snapshot with an arbitrary total, hoping the real end-to-end harness turns out to silently re-pin the sentinel the way every other testnet-based scenario conveniently does. | A0 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EGenesisIdentityTest.java#anUnmodifiedTestnetProfileAcceptsAnArbitraryGenesisTotalThroughTheRealHarness` |
| E2E-43 | Sit as a peer with an incompatible genesis for ten real sync rounds, hoping the ban arithmetic drifts from the constants' own math and either evicts early, never evicts, or keeps drawing requests after eviction. | A2 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EGenesisBanScoreTest.java#anIncompatibleGenesisPeerIsBannedAtExactlyTheTenthStrikeAndThenReceivesNoFurtherRequests` |
| E2E-44 | Serve a non-JSON `/block` body over a real socket, hoping the malformed-response penalty is confused with the cheaper genesis-mismatch penalty and either bans too early or never at all. | A2 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EGenesisBanScoreTest.java#aMalformedBlockResponseIsPeerInvalidNeverConfusedWithIncompatibleAndBansOnlyAtTheThirdStrike` |
| E2E-45 | Sit an honestly misconfigured (same total, different distribution) real node in a fully-meshed three-node network for a bounded run of repeated sync rounds, hoping its steady stream of genesis mismatches poisons the two genuinely-agreeing peers' convergence or leaves growing per-round state behind. | A0 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EGenesisDivergenceTest.java#anHonestlyMisconfiguredPeerInAFullMeshNeverDestabilizesTheHonestPairOrAdoptsTheirChain` |
| E2E-46 | Serve a hostile peer's near-perfect forgery of the victim's own real genesis -- one field altered by exactly one unit -- hoping a forgery this close is special-cased as "near enough" instead of refused by the same flat hash-divergence rule as a wildly different genesis. | A2 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EGenesisEclipseTest.java#aNearPerfectGenesisForgeryWithExactlyOneFieldAlteredIsRejectedLikeAnyOtherMismatch` |
| E2E-47 | Boot a node twice from the same genesis-supply-mismatched snapshot -- once via an aborted snap-sync bootstrap against an honest peer, once via direct startup with no peer at all -- hoping the silent WARN-and-fall-through around a failed bootstrap attempt also softens or masks the pin-mismatch refusal that follows it. | A6 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EGenesisIdentityTest.java#aGenesisSupplyPinMismatchRefusesBootIdenticallyViaAnAbortedSnapSyncOrDirectBoot` |
| E2E-48 | Configure a real, separate `java -cp ...` OS process's genesis snapshot selection (file override, shipped resource, empty) through the real shell environment, hoping the real `System.getenv()` path or the real classpath-resource load disagrees with what the injected-lookup-function unit tests already lock down. | A0 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EGenesisProcessBootTest.java#configPrecedenceHoldsAcrossFileResourceAndEmptyThroughRealOsProcesses` |
| E2E-49 | Point `RHIZOME_SNAPSHOT` at a 600 MiB file, hoping a real process either binds its HTTP port before rejecting the oversize or never rejects it at all, turning a misconfiguration into a reachable-but-broken node instead of a clean refusal to start. | A6 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EGenesisProcessBootTest.java#anOversizedSnapshotFileFailsBeforeTheProcessEverBindsItsPort` |
| E2E-50 | Point `RHIZOME_SNAPSHOT` at a path that does not exist, then immediately retry with a correct config at the exact same data directory, hoping the first aborted real process either fails late (port already open) or leaves a RocksDB lock/partial state that poisons the operator's very next attempt. | A6 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EGenesisProcessBootTest.java#aMissingSnapshotPathFailsCleanlyAndLeavesNoResidualLockForARetry` |
| E2E-51 | Restart a real node on its own data directory with a "governance revision" allocation artifact that carries the SAME pinned total but a DIFFERENT balance distribution, hoping the boot-time pin check (which only compares totals) is the only gate and the restart silently rewrites who owns the genesis coins. | A6 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EGenesisRestartTest.java#redistributingThePinnedTotalOnARealRestartIsRefusedByCommitmentReVerification` |
| E2E-52 | As above but against a genuinely non-trivial 9-block chain, hoping a refused restart truncates, silently re-derives, or partially overwrites even one of the blocks that were already real and buried. | A6 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EGenesisRestartTest.java#aNineBlockChainSurvivesARefusedRestartCompletelyUntouched` |
| E2E-53 | Restart a real mainnet node's data directory under a testnet profile and a testnet-appropriate snapshot (the shape of flipping `RHIZOME_NETWORK` without realising the directory already holds a different network's chain), hoping the node comes up as if the directory were a fresh, empty testnet chain rather than refusing the mismatch. | A6 | DEFENDED | `app-node/src/test/java/rhizome/adversarial/e2e/E2EGenesisRestartTest.java#flippingTheNetworkProfileOnAnExistingDataDirectoryIsRefusedNotReinterpreted` |
| E2E-54 | Seed a real node's genesis snapshot with the SAME 25-byte address spelled once in uppercase hex and once in lowercase hex, hoping the case-insensitive address parse silently merges the two entries into one wallet -- one amount discarded, the survivor chosen by map iteration order rather than the file -- and that nothing downstream notices, least of all on an unpinned profile with no supply pin to re-check the merged total. | A6 | DEFENDED | The two hex spellings decode to an EQUAL `PublicAddress` (`java.util.HexFormat` parses hex case-insensitively even though `Hex.bytesToHex` always renders uppercase, and `org.json` does not case-fold object keys). Confirmed RESIDUAL on 2026-08-22 (the second `put()` silently overwrote the first entry's amount; which survived was an artifact of `org.json`'s internal `HashMap` iteration order); fixed 2026-08-23: `LedgerSnapshot.fromJson` rejects the second key that decodes to an already-present address, the same fail-loud ingress rule as the high-bit balance guard one statement up (audit F3), so a case-collided file never reaches seeding, the supply-pin check, or the genesis commitment at all -- on ANY profile, pinned or not. Proven, through real separate-process boots on both a pinned (mainnet) and an unpinned (testnet) profile, each asserting a non-zero exit naming the duplicate before the port ever binds (and, on mainnet, that the refusal is the duplicate guard's message rather than the pin check's), by `app-node/src/test/java/rhizome/adversarial/e2e/E2EGenesisCaseCollisionTest.java#duplicateAddressUnderTwoHexCasesIsRefusedAtSnapshotLoadBeforeAnyPortBinds`; the decode-level rejection is unit-locked by `lib-core/src/test/java/rhizome/LedgerSnapshotTest.java#rejectsCaseVariantSpellingsOfTheSameAddress`. |
| E2E-55 | Race a symlink named by `RHIZOME_SNAPSHOT` between `SnapshotLoader.fromFile`'s size check and its content read, hoping the two filesystem operations observe two different targets so a size cap enforced against the first is silently bypassed by whatever the second happens to read. | A6 | DEFENDED | `fromFile` now opens the path exactly once (a single `FileChannel`) and derives both the size check and the content read from that one handle, so a symlink retargeted after the open cannot affect the already-open descriptor on POSIX systems -- there is no longer a window between two independent filesystem calls for a race to land in. A background thread continuously re-pointing the symlink between a tiny valid snapshot and a ~513 MiB one (over the 512 MiB cap) across 1000 racing `fromFile` calls produced zero bypasses, where the pre-fix version reliably produced at least one within 300-600 attempts. Proven (in a forked process with an explicit `-Xmx`, since the ~513 MiB race target cannot safely round-trip through this module's fixed 512 MiB Gradle test-worker heap) by `app-node/src/test/java/rhizome/periodic/e2e/E2EGenesisExoticPathsTest.java#symlinkRetargetedBetweenTheSizeProbeAndTheReadNoLongerBypassesTheSizeCap` (manual/periodic bucket -- see the class javadoc). |
| E2E-56 | Point `RHIZOME_SNAPSHOT` at a directory through a real child process, hoping the directory read surfaces as an unhandled or confusing exception, or that the process binds its port before the read is even attempted. | A6 | DEFENDED | `app-node/src/test/java/rhizome/periodic/e2e/E2EGenesisExoticPathsTest.java#aDirectoryAsTheSnapshotPathFailsCleanlyThroughARealProcessBoot` (manual/periodic bucket). |
| E2E-57 | Point `RHIZOME_SNAPSHOT` at a FIFO with no writer through a real child process, hoping the boot either fails within a sane budget or hangs unnoticed forever, indistinguishable from a slow-but-eventually-successful start. | A6 | DEFENDED | `fromFile` now stats the resolved path's type (`Files.readAttributes`, which does not block on a FIFO -- verified empirically to return in under a millisecond) and refuses anything that is not a regular file before ever calling the open that could block. A real child process pointed at a writerless FIFO now exits almost immediately with a clean, typed error and never opens its port, where it previously neither exited nor opened its port within a 10 second observation budget. The one part NOT fully closed: if the resolved target's type changes in the narrow window between that stat and the subsequent open, `fromFile` falls back to a bounded open+read (a fixed, generous timeout) rather than a hang -- verified empirically that `Thread.interrupt()` does not unblock a thread already stuck inside `FileChannel.open()` on a writerless FIFO, so that specific race is bounded (the boot thread gives up; a daemon thread is abandoned) rather than eliminated. That narrower race is not what this test exercises. Proven by `app-node/src/test/java/rhizome/periodic/e2e/E2EGenesisExoticPathsTest.java#aFifoWithNoWriterNowFailsFastInsteadOfHangingTheBoot` (manual/periodic bucket). |
| E2E-58 | Boot a real node process against a genesis snapshot with a large wallet count, hoping `GenesisLedger.seed`'s undocumented, uncapped per-wallet loop turns out to be free, or at least sublinear, so a large-but-otherwise-valid allocation file cannot be used to stall a node's own boot. | A6 | DEFENDED | First measured at ~3.3-3.6 ms per wallet against a real node process: one synchronous, fsync'd RocksDB write per wallet (two, actually -- `createWallet` then `deposit`), not the batched write the durability contract elsewhere in this module already uses for a block's own ledger writes. An 8,000-wallet snapshot alone cost ~28 s of boot time beyond baseline, and the rate implied a several-hundred-thousand-wallet file already cost multiple minutes, a several-million-wallet one multiple hours -- a confirmed startup DoS via an otherwise perfectly valid genesis allocation. Fixed with a `Ledger#beginBulkLoad()`/`#endBulkLoad()` window (default no-op; `RocksDbNodeStore.RocksLedger` buffers the SAME per-entry hasWallet/createWallet/deposit calls in memory and flushes them in chunked 10,000-wallet `WriteBatch`es) -- a pure batching change, so the resulting balances are byte-identical (`LedgerContract`'s bulk-load contract test covers both ledger implementations; `GenesisBlockTest`/`LedgerSnapshotTest` pass unmodified). Re-measured at ~0.08 ms (~80 microseconds) per wallet, a ~40-45x reduction: a raised, more representative 50,000/200,000-wallet pair now boots in ~3.5-3.6 s / ~15-18 s respectively, comfortably inside a 60 s ceiling, and a seven-figure wallet count is now on the order of a minute rather than hours. Proven by `app-node/src/test/java/rhizome/periodic/e2e/E2EGenesisLargeSnapshotStartupTest.java#startupTimeScalesRoughlyLinearlyWithWalletCountAndStaysBounded` (manual/periodic bucket -- kept there: still slower than this bucket's fast-gate peers, and the placement was never about this scenario's verdict). |
| E2E-59 | Feed `SnapshotLoader.fromResource`'s size-unknown (`getContentLengthLong() == -1`) fallback branch a real, uninstrumented, ordinary-sized resource under a constrained heap, hoping the branch nobody could previously test (no injection seam on a bare-`String` method) turns out to break normal operation, not just an extreme case. | A6 | DEFENDED | An 8 MiB resource completes cleanly under a 64 MiB heap. Proven, via a forked process with a classloader that shadows only `SnapshotLoader` so a fabricated `URLConnection` can be attached without a JVM-global `URLStreamHandlerFactory`, by `app-node/src/test/java/rhizome/adversarial/e2e/E2EGenesisSnapshotFallbackTest.java#aResourceComfortablyUnderTheHeapCompletesWithoutOom`. |
| E2E-60 | Feed the same fallback branch a resource well under the real 512 MiB pinned cap but larger than a small deployment's heap, hoping the branch streams or bounds its read against the CONSUMING process's own memory rather than buffering the whole declared-unknown-size body. | A6 | RESIDUAL | Investigated and partially closed, not eliminated: `readAtMost` was rewritten to read fixed-size (64 KiB) chunks merged once into a right-sized array, instead of a `ByteArrayOutputStream` whose doubling growth plus its own `toByteArray()` copy held two-to-three full-size copies of the content alive at once. Root-cause isolation found the OOM has two independent causes, not one -- `readAtMost`'s own buffering overhead (now reduced: a 64 MiB heap's practical full read+parse ceiling moved from ~10 MiB to ~25-29 MiB), AND `org.json`'s DOM parse independently roughly doubling-to-tripling peak memory over the raw byte count regardless of how the bytes were read. A 200 MiB resource (four times under the 512 MiB cap) still reliably `OutOfMemoryError`s under a 64 MiB heap after the fix, unchanged from before it -- closing this fully would mean parsing JSON from a bounded stream directly rather than ever materializing the whole decoded body, a materially larger change than this fix's scope. Proven, with the two-cause finding and the fix's measured (non-)effect both asserted, by `app-node/src/test/java/rhizome/adversarial/e2e/E2EGenesisSnapshotFallbackTest.java#aResourceWellUnderTheCapButOverTheHeapStillOomsInsteadOfStreaming`. |

---

## Known gaps

Scenarios the protocol names but does not yet prove. Each entry here would be because closing it
needs work beyond writing a test — a fixture that does not exist, or a decision that has not been
taken.

| ID | Scenario | Why |
|----|----------|-----|
| E2E-61 | A GraalVM native-image binary of the node resolving the embedded genesis resource identically to the JVM build, and failing cleanly (not crashing, not silently substituting a different genesis) if the reachability-metadata entry for that classpath resource is ever stripped from `app-node/src/main/resources/META-INF/native-image/`. | `native-image` is not installed in this development environment (confirmed in `WHITEPAPER.md`), so no test that actually builds and runs the native binary can execute here. This is future work once a GraalVM SDK is available on the box that runs this suite -- see `./gradlew :app-node:nativeImage`'s own guard for the same absence. Reserved id: the next new `E2E` scenario added to the table above should start at E2E-62, not reuse this one, since this row is intentionally excluded from the family's dense-numbering check. |

## Change log

- **2026-08-23** — Post-implementation-review follow-ups: one RESIDUAL closed outright, and the
  E2E-58 fix's crash-atomicity gap closed with a guard rather than a claimed atomicity:
  - **E2E-54 (case-collision duplicate addresses) → DEFENDED.** `LedgerSnapshot.fromJson` now
    rejects the second JSON key that decodes to an already-present `PublicAddress` — the same
    fail-loud ingress rule as the high-bit balance guard one statement up (audit F3). The
    RESIDUAL this replaces was real and empirically verified (the surviving amount was an
    artifact of `org.json`'s internal `HashMap` iteration order, never of the file), and it was
    sharpest on UNPINNED profiles (testnet/devnet), where no genesis-supply pin re-checks the
    merged total downstream — the merged result was simply committed. The E2E proof is rewritten
    accordingly: instead of pinning the merge, it now boots real, separate processes on BOTH a
    pinned (mainnet) and an unpinned (testnet) profile and asserts each refuses at snapshot
    load — non-zero exit, the duplicate named in stderr, no port ever bound, and on mainnet the
    refusal carries the duplicate guard's message rather than the pin check's. The decode-level
    rejection is unit-locked by
    `lib-core/src/test/java/rhizome/LedgerSnapshotTest.java#rejectsCaseVariantSpellingsOfTheSameAddress`.
  - **Torn genesis-seed double-deposit → refused at boot.** The E2E-58 bulk-load window flushes
    in independent, synced 10,000-wallet chunks — deliberately NOT crash-atomic — so a crash
    mid-flush left a durable partial seed at height 0, and the next boot's re-seed
    (`GenesisLedger.seed` tops existing wallets up) would deposit those wallets' amounts a
    SECOND time while the genesis header committed the snapshot's own, correct total: a silent
    supply inflation. Rather than grow a second marker mechanism next to snap-sync's (audit M8),
    crash safety now comes from the same RULE the marker implements — never run on half-seeded
    data — enforced at the one place a torn seed is unambiguous: `GenesisBlock.initChain`
    refuses to seed over a non-empty ledger, which at height 0 can only be a torn prior seed
    (a block's ledger writes commit atomically with its height; snap-sync's bootstrap never
    lands at height 0). The refusal names the remedy: wipe the data directory and re-seed.
    Proven by `GenesisBlockTest#initChainRefusesToSeedOverANonEmptyLedger` (unit level, ledger
    left untouched) and `RocksDbNodeStoreTest#aNonEmptyLedgerAtHeightZeroRefusesTheFreshChainBoot`
    (the durable, across-reopen shape). `Ledger`'s bulk-load javadoc now states plainly that the
    "byte-identical to an unbatched run" claim holds for a window that runs to completion, and
    why crash recovery is not the window's job.
  - **The bulk-load contract test now proves what its name claims.**
    `LedgerContract#bulkLoadWindowIsReadYourWritesAndMatchesUnbatchedWrites` previously asserted
    read-your-writes and final balances but never ran the unbatched comparison its name (and
    the E2E-58 entry below) claimed; it now runs the identical call sequence on a second, fresh
    ledger with no window open and asserts whole-ledger equality. Making that possible exposed
    that `RocksDbNodeStoreTest`'s contract factory quietly returned the SAME store on a second
    `newLedger()` call within one test method — against the contract's "a fresh, empty ledger"
    — and now opens a fresh store per call. A second contract test crosses the 10,000-wallet
    flush-chunk boundary with 10,001 wallets, a path nothing in `./gradlew build` previously
    exercised (only the periodic E2E-58 scale test did).
- **2026-08-23** — `GenesisLedger.seed` fixed against E2E-58 (the fourth RESIDUAL finding from
  the 2026-08-22 build-out; the other three are the `SnapshotLoader` entry directly below):
  - **E2E-58 (per-wallet genesis-seed fsync) → DEFENDED.** Root cause confirmed empirically, not
    assumed: each snapshot entry paid TWO synchronous, fsync'd RocksDB writes
    (`RocksDbNodeStore.RocksLedger`'s `createWallet` then `deposit`, each going straight to
    `db.put` with `WriteOptions.setSync(true)`), instead of the atomic-`WriteBatch` pattern this
    module already uses for a block's own ledger writes (`RocksChainStore.append`). Fixed with a
    new `Ledger#beginBulkLoad()`/`#endBulkLoad()` window — default a no-op, so the in-memory
    reference ledger and its existing callers are untouched — that `GenesisLedger.seed` now opens
    around its loop; `RocksDbNodeStore.RocksLedger` overrides it to buffer the SAME
    hasWallet/createWallet/deposit calls in the block-commit staging map it already had
    (`pendingLedger`) and flush them on `endBulkLoad()` in chunked 10,000-wallet `WriteBatch`es
    instead of a write per wallet. This is a pure batching change — `GenesisLedger.seed`'s loop
    body, validation order and checked arithmetic are byte-for-byte unchanged, so the same
    snapshot produces the identical resulting balances, genesis hash and state root as before
    (`LedgerContract`'s new bulk-load contract test — read-your-writes inside the window, and the
    post-window state matching an unbatched run — covers both the in-memory and RocksDB ledgers;
    `GenesisBlockTest`, `LedgerSnapshotTest` and `NetworkParametersTest` pass unmodified).
    Re-measured (same box, same methodology) at ~0.08 ms/wallet, down from ~3.3-3.6 ms/wallet — a
    ~40-45x reduction in the marginal per-wallet cost. At the original 2,000/8,000-wallet pair the
    fix makes seeding cheap enough to vanish into this test's own ~0.9-1.2 s of fixed per-process
    overhead (JVM start, RocksDB open) — itself a sign the fix works, but too noise-dominated for
    a clean scaling assertion — so the test's `SMALL`/`LARGE` were raised to 50,000/200,000 (still
    `LARGE / 4`, the design's original N-vs-N/4 shape), which boot in ~3.5-3.6 s / ~15-18 s
    respectively: a stronger proof (a seven-figure wallet count is now on the order of a minute,
    not hours) that also finishes faster in total than the ~35 s the old 2,000/8,000 pair used to
    cost. Stays in the `rhizome.periodic.e2e` manual bucket rather than moving into the fast
    `./gradlew adversarial` gate: at ~19 s combined it would still fit, but the placement was
    never about this scenario's verdict, only its cost relative to that gate's other, much
    cheaper, scenarios — and it remains one of the more expensive in its own bucket.
- **2026-08-23** — `SnapshotLoader` hardened against two of the three RESIDUAL findings the
  previous entry's build-out discovered, and the third was investigated further without being
  fully closed:
  - **E2E-55 (symlink TOCTOU) → DEFENDED.** `fromFile` used to probe `Files.size(path)` and
    later call `Files.readString(path)` as two independent filesystem operations; it now opens
    one `FileChannel` and derives both the size check and the content read from that single
    handle, so a symlink retargeted after the open cannot affect the already-open descriptor on
    POSIX. Re-run of the same 1000-attempt race that reliably bypassed the cap before the fix now
    produces zero bypasses.
  - **E2E-57 (FIFO hang) → DEFENDED for the case tested.** `fromFile` now stats the resolved
    path's type via `Files.readAttributes` (which does not block on a FIFO — verified empirically
    to return in well under a millisecond) and refuses anything that is not a regular file before
    ever calling the open that could block. A narrower residual remains and is stated plainly
    rather than hidden: if the resolved target's type changes in the window between that stat and
    the subsequent open, `fromFile` falls back to a bounded open+read (a fixed timeout) rather
    than eliminating the race outright, because `Thread.interrupt()` was verified empirically NOT
    to unblock a thread already stuck inside `FileChannel.open()` on a writerless FIFO (the
    `open()` syscall itself is not wired into Java NIO's interruptible-channel machinery the way
    `read()`/`write()` are). That race is bounded (the boot thread gives up after a timeout,
    abandoning a daemon thread rather than hanging), not proven eliminated — it is not what
    E2E-57's test exercises, which points a FIFO at the path directly with no race.
  - **E2E-60 (`fromResource` fallback OOM) stays RESIDUAL, root-caused further.**
    `SnapshotLoader.readAtMost` was rewritten from a `ByteArrayOutputStream` (whose doubling
    growth plus its own `toByteArray()` copy held two-to-three full-size copies of the content
    alive at once) to fixed-size (64 KiB) chunks merged once into a right-sized array. Root-cause
    isolation (a standalone instrumented probe, not part of the checked-in suite) found the OOM
    has two independent causes: the read path's own buffering overhead (now measurably reduced —
    a 64 MiB heap's practical full read+parse ceiling moved from content around 10 MiB to roughly
    25-29 MiB) and, independently, `org.json`'s DOM parse itself roughly doubling-to-tripling peak
    memory over the raw byte count regardless of how carefully the bytes were read (confirmed:
    content whose read and UTF-8 decode succeeded outright still `OutOfMemoryError`'d during
    `JSONObject` construction alone). Neither cause, nor both together, comes close to fitting a
    200 MiB body in a 64 MiB heap; E2E-60's test still asserts (and still observes) the OOM,
    unchanged. Fully closing this would mean parsing JSON from a bounded stream directly instead
    of ever materializing the whole decoded body — a materially larger change (a different
    JSON-parsing strategy) than this fix's scope covers, so it is declared here rather than forced
    or silently narrowed.
  - None of the three fixes changed genesis hashing or any decode result for a well-formed,
    within-cap snapshot: `fromFile`'s single-handle read decodes the identical bytes the old
    probe-then-read pair did for any file that does not change between the two steps, and
    `readAtMost`'s chunked merge produces byte-identical output to the old buffered read for any
    input under the cap. `GenesisBlockTest`, `LedgerSnapshotTest`, `ChainEngineTest` and
    `NetworkParametersTest` (`lib-core/src/test/java/rhizome/`) pass unmodified.
- **2026-08-22** — Seven more `E2E` scenarios (E2E-54..60) close the gaps the design's Phase 5
  build-out targeted, all under `contracts/genesis-allocation-format.md`'s snapshot-loading and
  boot surface rather than the pin-check surface E2E-37..53 already covered. Two placement
  decisions follow directly from measuring, not guessing, each scenario's real cost:
  - E2E-54 (case-collision addresses) and E2E-59/60 (the `fromResource` size-unknown fallback,
    forked with an explicit `-Xmx` per case) measured at a few seconds total and stayed in
    `rhizome.adversarial.e2e`, inside the fast `./gradlew adversarial` gate.
  - E2E-55..58 (the exotic-file-paths trio and the large-snapshot startup-cost scenario) measured
    at ~20 s and ~35 s respectively -- disproportionate next to this package's few-second peers --
    and moved to a new `rhizome.periodic.e2e` package (app-node test sources), which matches
    neither of the `adversarialTest` Gradle task's `includeTestsMatching` filters
    (`rhizome.adversarial.*`, or a class name ending `AttackTest`/`AdversarialTest`) and is
    therefore invisible to both `./gradlew adversarial` and a routine `./gradlew build`/
    `./gradlew test`. A new `:app-node:periodicAdversarial` Gradle task (not a dependency of
    `test`, `check`, `build` or `adversarial`) is the only way to run them; `AdversarialProtocolTest`
    still resolves their citations regardless of location; its reverse (tree-to-catalogue) check
    does not, since that check is scoped to the same two Gradle filters by design. Two new,
    package-local pieces of infrastructure support this without touching any Phase 1-4 file:
    `rhizome.testsupport.SubprocessRunner` (public, cross-package: launches an arbitrary
    `main(String[])` class as a real process with caller-chosen JVM flags -- `ProcessHarness`
    cannot, being package-private inside `rhizome.adversarial.e2e` and hardcoded to
    `RhizomeNode.main`) and `rhizome.periodic.e2e.MinimalNodeProcess` (a trimmed,
    same-package copy of `ProcessHarness`'s node-launching technique, needed only because
    `ProcessHarness` itself is off-limits to a class outside its package).
  - Three of the seven are RESIDUAL, newly discovered rather than assumed: E2E-55 (a real,
    reliably-reproduced TOCTOU bypass of `SnapshotLoader.fromFile`'s size cap via a raced
    symlink), E2E-57 (a real, unbounded boot-thread hang reading a writerless FIFO, with no
    timeout anywhere on the path), and E2E-58 (a real, roughly-linear-but-uncapped ~3.3-3.6 ms
    per-wallet cost in `GenesisLedger.seed`, making a several-hundred-thousand-wallet snapshot a
    multi-minute startup DoS on its own). E2E-60 is also RESIDUAL: the `fromResource` fallback
    path buffers rather than streams, so a resource well under the documented 512 MiB cap can
    still `OutOfMemoryError` a memory-constrained node. E2E-54, E2E-56 and E2E-59 confirmed the
    less alarming outcome instead (a silent-but-harmless merge, a clean refusal, and normal
    operation for an ordinary-sized resource, respectively) -- each was investigated empirically
    before its assertion was written, per the design's explicit instruction not to assume either
    outcome.
  - E2E-61 (native-image genesis resolution) is recorded in Known gaps instead of proven: this
    development environment has no `native-image` binary.
- **2026-08-22** — Three more `E2E` scenarios (E2E-51..53) close the last gap in the genesis-
  restart surface: every scenario above this one either boots a node cold or restarts it on an
  UNCHANGED configuration (`E2ENodeResilienceTest`'s existing precedent); none restart a node whose
  configuration silently changed underneath its own existing store. New `TestNetwork` capability:
  `reopen(String name)`, which stops the node currently tracked under `name` (idempotent,
  `public synchronized RhizomeNode.close()`) and returns a fresh `Builder` pre-bound to the exact
  same data directory and the freed port, ready for `.params(...)`/`.snapshot(...)`/`.start()`
  again -- generalising `E2ENodeResilienceTest`'s single-node "close, rebuild over the same
  directory" pattern into a network-level convenience that only touches the one named node, and
  replacing that name's bookkeeping entry so a `.start()` that throws (every scenario here) leaves
  nothing for `TestNetwork.close()` to double-close. New suite `E2EGenesisRestartTest`, all three
  scenarios driving `ChainEngine.Boot.build()`'s stored-genesis re-verification (the `else if`
  branch guarding a non-empty store) through a real restart. E2E-51 restarts a real node with a
  same-pinned-total, different-DISTRIBUTION allocation artifact (a future governance revision that
  reallocates `S0` without touching the pinned constant) and proves the refusal, then proves a
  THIRD reopen with the ORIGINAL distribution restores the exact pre-refusal chain, showing the
  refused middle attempt never touched the on-disk store. E2E-52 is the same proof against a
  genuinely non-trivial 9-block chain, checked block by block. E2E-53 flips the network profile
  (mainnet data directory, testnet params + a testnet-appropriate snapshot -- the shape of changing
  `RHIZOME_NETWORK` without realising the directory already holds a different chain) and proves the
  restart is refused rather than the store being silently reinterpreted as a fresh, empty testnet
  chain at height 0. All three were assumed, going in, to plausibly throw `IllegalArgumentException`
  from `GenesisBlock.build`'s own guards (the pin-total check for E2E-51/52, the chainId-mismatch
  check for E2E-53) -- empirically, none do: because each scenario's replacement snapshot is
  internally consistent with the params it is paired with (same total for E2E-51/52; matching
  testnet chainId for E2E-53), `GenesisBlock.build` itself succeeds every time, and the refusal
  instead comes from `ChainEngine.Boot.build()`'s generic stored-genesis hash comparison against
  what is already on disk -- one message, "Stored genesis does not match network parameters and
  snapshot", identical `IllegalStateException` across all three, naming neither total nor chainId
  (unlike the genuinely-mismatched-total case `E2EGenesisIdentityTest`'s E2E-47 asserts on). A
  snapshot deliberately inconsistent with its own params (e.g. a testnet profile paired with a
  leftover mainnet-chainId snapshot file) would instead hit `GenesisBlock.build`'s own
  `IllegalArgumentException` before `matches()` is ever reached; this suite's three scenarios simply
  do not construct that particular combination.
- **2026-08-22** — Three more `E2E` scenarios (E2E-48..50) close a gap none of the network-level
  proofs above could reach at all: every one of them, and every other suite in
  `rhizome.adversarial.e2e`, runs `RhizomeNode` as a real Java object sharing the test JVM, so none
  can observe "this process never opened a socket" or exercise the real `System.getenv()`-backed
  `NodeConfig.fromEnv()` no-arg overload `RhizomeNode.main` actually calls -- every existing config
  test, including `NodeConfigFromEnvTest`, goes through the injected-lookup-function overload
  instead. New shared fixture, `ProcessHarness` (no `@Test` methods -- a fixture like
  `HostilePeer`/`E2EFixtures`, scanned by the gate anyway since it lives in the same package):
  launches `rhizome.node.RhizomeNode.main(String[])` as a genuine `java -cp ...` child process via
  `ProcessBuilder`, resolving the launcher the same way the current JVM was started
  (`ProcessHandle.current().info().command()`) rather than assuming `java` is on `PATH`, draining
  stdout/stderr on two independent daemon threads so neither pipe's OS buffer can deadlock a
  `waitFor()`, and polling a raw TCP connect for port liveness -- needed both to prove a port DID
  open and, negated, that it NEVER did within a bounded window. The classpath passthrough
  (`System.getProperty("java.class.path")` verbatim as the child's `-cp`) was verified empirically
  before any scenario was written on top of it: on this toolchain the Gradle test worker's own
  classpath is already an explicit, colon-separated list of real jar/directory paths, not a
  manifest-jar wrapper, so passing it straight through resolves identically -- confirmed by
  launching a real child and watching it bind a port and answer `/block?blockId=1` in under a
  second. E2E-48 boots three real processes (testnet/no-override, mainnet/no-override,
  mainnet/file-override with a same-total-different-distribution snapshot) and checks the reported
  genesis over each one's real HTTP API, cross-checking the no-override mainnet case against
  `GenesisBlock.build()` recomputed independently in the test JVM from the identical shipped
  resource -- the same independent-recomputation shape E2E-38 already uses, now proven through a
  real environment instead of an injected one. E2E-49 and E2E-50 both prove a fail-fast boot at the
  socket level, not just via an absent Java exception: a sparse (no real disk burned) 600 MiB
  `RHIZOME_SNAPSHOT` file, and a `RHIZOME_SNAPSHOT` naming a path that does not exist, each refuse
  boot with the real `SnapshotLoader`/`Files.size` message in stderr, a non-zero exit, and -- the
  decisive claim an in-JVM test cannot make -- zero successful TCP connects to the configured port
  across a bounded poll window. E2E-50 adds the scenario's distinguishing check: a second,
  correctly-configured real process pointed at the exact same data directory the first one aborted
  in starts cleanly, with no `RocksDBException` in its stderr -- true today because
  `RhizomeNode.assemble` loads the snapshot before opening any store, a structural fact this test
  now pins so a future reordering would be caught the moment it started to matter, rather than
  only if an operator's retry happened to hit it in production. The wall-clock cost of proving two
  negatives (a port that never opens) is unavoidable but bounded deliberately tight: the poll
  window is ten-plus times the empirically observed sub-200 ms fail-fast cost, not a round-number
  guess, keeping the new suite's total added time to the `adversarial` gate under ten seconds.

- **2026-08-22** — Five more `E2E` scenarios (E2E-43..47) close the network-level gaps a follow-up
  review found in the previous entry's six: nothing had proven the sync driver's ban-score
  arithmetic against a real genesis-incompatible peer down to the exact strike that bans it (not
  the one before, not the one after), nor that a banned/evicted peer stops being contacted at all
  rather than merely stops mattering; nothing had proven a malformed, non-JSON `/block` response
  stays classified `PEER_INVALID` rather than drifting into the cheaper `INCOMPATIBLE` bucket
  through real JSON parsing; nothing had proven that an honestly misconfigured (not hostile) peer's
  steady stream of genesis mismatches in a real multi-node mesh leaves the genuinely-agreeing peers
  converging and unharmed; nothing had proven that a near-perfect genesis forgery -- one field off
  by one unit, not a wildly different block -- is refused by the same flat hash-divergence rule as
  any other mismatch, with no special-casing for "close"; and nothing had driven the genesis-supply
  pin-mismatch refusal through the ACTUAL silent-WARN-and-fall-through path around a failed
  snap-sync bootstrap attempt, rather than only the direct-boot path the existing unit tests cover.
  Two new suites (`E2EGenesisBanScoreTest` for E2E-43/44, class A2; `E2EGenesisDivergenceTest` for
  E2E-45, class A0) plus one scenario added to each of the two existing genesis suites (E2E-46 in
  `E2EGenesisEclipseTest`, class A2; E2E-47 in `E2EGenesisIdentityTest`, class A6). `HostilePeer`
  gained three additive fixture primitives, none of which changed any existing test's behaviour:
  `claimsGenesis(Supplier<Block>)` (a general primitive for the fork-detection probe's answer at
  height 1, which `sharesGenesisWith` is now expressed in terms of, letting a scenario serve a
  tampered near-forgery rather than only a real node's real genesis), `servesBlock(Supplier<byte[]>)`
  (the `/block` analogue of the pre-existing `servesHeaders`, a fixed byte stream bypassing normal
  JSON serialization), and a request counter (`requestCount()`) so a scenario can assert a banned
  peer receives zero further requests, not merely that its lies stop mattering. Writing E2E-43/44
  surfaced a latent fixture gap worth recording: `HostilePeer`'s default/`claimsWork` `/total_work`
  body is a bare decimal string, but `HttpPeerSource.totalWork()` requires the real wire envelope
  (`{"totalWork": "..."}`, mirroring `NodeApi`) -- every existing scenario using `claimsWork` had
  therefore always been hitting an earlier, unrelated malformed-response classification rather than
  the one its javadoc described, invisibly, because none of them asserted the specific ban-score
  classification. Not fixed in the shared fixture (fixing the default would not change any existing
  assertion, since none of those scenarios check classification, but the safer and already-precedented
  fix -- matching `E2ESupplyCommitmentTest`'s own workaround -- is for a scenario that needs a
  genuinely well-formed `/total_work` to pass the properly-wrapped JSON itself); both new scenarios
  here do exactly that.

- **2026-08-22** — Six new `E2E` scenarios (E2E-37..42) close network-level gaps a
  post-implementation review flagged for the pinned genesis supply / shipped allocation feature
  (branch `003-genesis-allocation`): the `GENESIS` family and the existing genesis/snapshot suites
  prove the pin as a pure function in one JVM, but nothing had driven a real, assembled
  `RhizomeNode` through the no-`RHIZOME_SNAPSHOT` classpath-resource fallback, proven that several
  independently-booted real mainnet processes actually land on a bit-identical genesis before any
  gossip runs (closing the substitution `plan.md` documented for spec SC-002), proven that a
  hostile peer offering a real-but-differently-distributed equal-total genesis or a fabricated,
  unrooted header stream cannot move a victim's or a freshly-joining node's chain identity, proven
  that a pruned node joining via snap-sync derives its genesis locally rather than from its (even
  honest) sync source, or exercised the unpinned `testnet()` sentinel through the real harness
  unmodified (every existing testnet-based `E2E` scenario re-pins it via `TestNetwork.FAST` for
  convenience). New suites: `E2EGenesisIdentityTest` (E2E-37, E2E-38, E2E-41, E2E-42; no adversary
  — class A0 — the property holds or fails from each node's own configuration and determinism) and
  `E2EGenesisEclipseTest` (E2E-39, E2E-40; class A2, a real hostile peer over a real socket). Zero
  new shared test infrastructure: both suites are built entirely on the existing `TestNetwork`,
  `HostilePeer` and `E2EFixtures`.
- **2026-08-22** — A new `GENESIS` family (3 scenarios) added for the pinned genesis supply
  (branch `003-genesis-allocation`): a per-network consensus constant `S₀`
  (`NetworkParameters.genesisSupply`, unpinned sentinel `GENESIS_SUPPLY_UNPINNED`) checked
  exactly against the loaded genesis snapshot's total in `GenesisBlock.build`, on every boot
  path, before any balance is seeded. All three scenarios were satisfied by tests already
  written for the feature (`GenesisBlockTest`, `LedgerSnapshotTest`) with zero new test code.
  GENESIS-01 is the mismatched-total boot refusal; GENESIS-02 proves the pin and the existing
  genesis commitment compose rather than duplicate — same total, different distribution passes
  the pin and is then caught by commitment re-verification — and that the chain-id and
  signed-range guards still run first; GENESIS-03 is the lockstep regression between the shipped
  mainnet allocation artifact (`genesis/rhizome-mainnet.json`) and the pinned constant, so an
  edit to one without the other fails the build rather than shipping a self-contradictory
  network definition. This family's scenario id is one character longer than any prior family's
  (`GENESIS` vs. `SUPPLY`/`WALLET`'s six), which `AdversarialProtocolTest`'s id pattern had never
  been asked to admit — widened from an unstated `{1,5}` bound to `{1,6}` rather than truncating
  the family name to fit an accidental limit.
- **2026-08-21** — A new `SUPPLY` family (11 scenarios) and three `E2E` scenarios (E2E-34..36) added
  for the supply header commitment (branch `002-supply-header-commitment`): the optional eleventh
  header field, `block.supply == parent.supply + Issuance.minted(...)`, prefix-closed and enforced
  by one `checkSupply` formula shared by `ChainEngine.addBlock` and header-only sync's
  `HeaderChain.validate`. Nine of the eleven `SUPPLY` scenarios were satisfied by tests already
  written for the feature (`SupplyCommitmentTest`, `GenesisBlockTest`, `CodecBoundsTest`,
  `HeaderChainTest`, `HeaderSynchronizerTest`) with zero new test code; two needed a new suite,
  `SupplyLedgerAttackTest` — SUPPLY-04 forces the accounting identity's own `Math.addExact` to
  overflow rather than silently wrap into a false match, and SUPPLY-07 feeds every binary decoder a
  header blob truncated exactly at the pre-feature 152-byte boundary, eight bytes short of the
  supply field, and confirms all three ingress paths refuse it loudly instead of misreading it as a
  legitimate supply-less header. All three `E2E` rows are new real-node, real-socket proofs
  (`E2ESupplyCommitmentTest`), extending the family to 36. E2E-34 posts a block whose `supply` field
  is forged after mining straight at a real node's `/submit` route and confirms
  `ChainEngine.addBlock`'s cheap supply check (WHITEPAPER §3.5 cheapest-first) refuses it before the
  now-stale nonce is ever re-verified, and that the node stays healthy and keeps producing. E2E-35
  serves a real syncing node a forged headers-only stream over a real socket, needing one
  shared-fixture extension — `HostilePeer` gained a `servesHeaders` capability, answering every
  `/headers` query with one fixed byte stream, same as the existing `/sync` case — which means the
  header run the victim actually validates fails at its first candidate rather than deep at the
  forged tail; the proof is written to the weaker-but-real claim the scenario allows: the victim's
  local chain, read back from its own engine, is provably untouched. E2E-36 forks two real mining
  nodes, converges them over real HTTP sync, and checks the two nodes' independently-read committed
  supply against a sum recomputed from the converged chain's own headers (`Issuance.minted`, real
  per-block difficulty and uncle refs, not a hardcoded number); it does not engineer divergent uncle
  inclusion between the two miners: no wiring exists in this harness to force one node's orphans
  into another's uncle set on a schedule.
- **2026-08-20** — The last three declared gaps closed, none of them the way they were declared.
  API-13 turned out to already be defended in code (`NodeApi.bearerMatches` uses
  `MessageDigest.isEqual`) with nothing enforcing it — closed with a structural tripwire
  (`TokenComparisonAttackTest`) plus a behavioural prefix-scale proof on a real socket (E2E-33).
  NET-11 turned out to be a sound design, not a missing fixture: `PeerBanList`'s score decays
  because a ban is culpability that must lapse, while `PeerDiscovery`'s consecutive-failure counter
  resets on success because it measures the length of the current outage — neither composes into a
  long-horizon eviction primitive against an honest peer, and the real limit is structural
  (`MAX_PER_SUBNET`) and conditional on an operator running a seed
  (`BanDiscoveryPartitionAttackTest`, BOUNDED). REORG-11 (selfish mining) needed the harness the gap
  note predicted, built as `SelfishMiningModel`: two real `ChainEngine`s, real proof of work, a
  seeded Bernoulli hash-rate draw, and every adoption decision run through the real
  `ChainSynchronizer`. On this chain the 2014 Eyal–Sirer numbers do not transfer unmodified — GHOST
  uncle rewards refund half a block to an orphaned miner, so an orphaned block is not a total loss —
  but the qualitative result holds: a 40%-hash-rate miner that withholds earns a share of issuance
  clearly above 40%, a 10% miner earns clearly below 10%, and enabling uncle rewards shrinks the
  edge without closing it (`SelfishMiningAttackTest`, BOUNDED). Building the harness surfaced an
  uncatalogued vector, closed alongside it: the fork-choice tie-break on an exact work tie compares
  tip hashes deterministically, so a miner can in principle grind for a smaller one — measured and
  bounded as REORG-12 (grinding costs a full PoW solve per attempt against a fair coin, for at most
  half a reward, which is negative expected value). The Known gaps table is now empty for the first
  time since this protocol was established.
- **2026-08-19** — Network layer added. A new `E2E` family of 32 scenarios covering the assembled
  system: fork convergence and partition heal between real mining nodes, a cross-branch double
  spend and its reorg-replay mirror on a shared premined genesis, HTTP abuse written on raw sockets
  (bearer, CSRF, DNS rebinding, read floods, malformed input, slow loris), a hostile peer serving
  unproven and undecodable branches over a real socket, a signed-transaction flood, and a restart
  on a live data directory, a contract deployed and called over HTTP, a poison block pushed at
  `/submit`, a liar sitting in two honest nodes' peer sets while they converge with each other, and
  box/token state reversing exactly on RocksDB through a peer-driven reorg, a pruned node
  refusing history it no longer holds, and a fresh node bootstrapping from a peer's snapshot.
  Twenty-four new proofs under `rhizome.adversarial.e2e`, plus eight
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
