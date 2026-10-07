# BE3-04 — Independent analytics regression matrix

## Purpose

This suite verifies BE3 analytics against independently derived expected
values. Expected values must not be calculated by calling the production
analytics functions.

## Precision policy

Exact `BigDecimal` values are used wherever the expected value terminates.

For recurring averages, the production contract uses
`AnalyticsFunctions.AVERAGE_MATH_CONTEXT` (`MathContext.DECIMAL128`).

No display rounding is performed by these tests.

## Rule matrix

| Requirement | Independent case | Expected result | Test |
|---|---|---|---|
| PER_100G scaling | 200 g, 165 kcal/100 g | 330 kcal | `per100gMass200Produces330CaloriesAnd20_10_40Macros` |
| PER_100G scaling | 150 g, 165 kcal/100 g | 247.5 kcal | `per100gMass150Produces247Point5CaloriesAnd15_7Point5_30Macros` |
| G-06 nutrition | original meal set | 930 kcal | `dailyNutrition930Then847Point5Then600` |
| G-06 nutrition | mass changed 200 → 150 g | 847.5 kcal | `dailyNutrition930Then847Point5Then600` |
| G-06 nutrition | variable meal deleted | 600 kcal | `dailyNutrition930Then847Point5Then600` |
| PER_SERVING | mass present but serving basis | unchanged | `perServingIsNotScaledByMass` |
| Status filtering | draft/cancelled/deleted | excluded | `draftCancelledAndDeletedEntriesDoNotParticipate` |
| Missing nutrients | no nutrient data | null + incomplete | `completelyUnknownNutritionIsNullAndIncomplete` |
| Partial nutrients | missing carbohydrate | carbohydrate null | `partiallyUnknownNutrientsRemainNullWithoutBecomingZero` |
| Empty day | no entries | null aggregate, 0 days | `emptyPeriodProducesNullAggregateAndZeroDaysWithData` |
| Daily total | two daily step totals | latest only | `dailyTotalsAreNotSummed` |
| Tie-break | equal occurredAt | updatedAt wins | `equalOccurredAtUsesUpdatedAt` |
| Tie-break | equal occurredAt + updatedAt | id wins | `equalOccurredAtAndUpdatedAtUsesIdAsStableTieBreak` |
| Sleep date | sleep crosses UTC midnight | wakeDate | `sleepBelongsToWakeDate` |
| Missing sleep date | no wakeDate | excluded | `sleepWithoutWakeDateIsNotAssignedToOccurredAtDate` |
| Local date | 22:00 UTC Warsaw | next local day | `WarsawUtcMidnightBelongsToNextLocalDay` |
| Missing date | confirmed meal without occurredAt | incomplete | `missingOccurredAtDoesNotBecomeAnArtificialNutritionDate` |
| Heart rate | latest pulse | latest value/context | `heartRateReturnsLatestValueAndQualifier` |
| Heart rate context | no qualifier | null qualifier | `heartRateWithoutQualifierKeepsQualifierAbsent` |
| Check-ins | four categories | four independent series | `checkinsKeepFourCategoriesSeparate` |
| Check-in gaps | day 1 + day 3 | no day 2 point | `checkinsKeepMissingDaysAsGaps` |
| Input order | reversed entries | identical result | `calculationsAreIndependentOfInputOrder` |

## Oracle examples

### Nutrition

Base food:

- energy: 165 kcal / 100 g
- protein: 10 g / 100 g
- fat: 5 g / 100 g
- carbohydrates: 20 g / 100 g

For 200 g:

```text
165 × 200 / 100 = 330 kcal
10  × 200 / 100 = 20 g protein
5   × 200 / 100 = 10 g fat
20  × 200 / 100 = 40 g carbohydrates
