# BE3-02 Normalisation

## Average precision policy

Core analytics returns daily metric averages as `BigDecimal`. Because recurring decimal fractions cannot be represented exactly, averages use `MathContext.DECIMAL128` (34 significant decimal digits, `RoundingMode.HALF_EVEN`) through the public `AnalyticsFunctions.AVERAGE_MATH_CONTEXT` constant.

The core layer does not apply a fixed display scale. Consumers may round or format the returned value for presentation, but that formatting policy must remain outside the core calculation.
