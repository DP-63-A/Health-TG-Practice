package org.healthtg.bot.text;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.healthtg.bot.text.TextParseResult.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent counterexamples and adjacent successful inputs for the first review findings. */
class TextInputReviewRegressionTest {
    private final TextInputParser parser = new TextInputParser();

    @ParameterizedTest @ValueSource(strings = {
            "двадцать десять", "двадцать одиннадцать", "тридцать девятнадцать",
            "сто двадцать одиннадцать", "две тысячи двадцать десять", "двадцать ноль",
            "сто одиннадцать один", "двадцать тридцать", "один двадцать",
            "двадцать десять тысяч", "один два тысячи"
    })
    void malformedWordNumbersAreNotAddedUp(String number) {
        var result = parser.parse("25.09.2026 за день прошла " + number + " шагов");
        assertEquals(NEEDS_CLARIFICATION, result.outcome(), result.toString());
        assertNoKnownNumber(result);
    }

    @ParameterizedTest @CsvSource(delimiter = '|', value = {
            "двадцать один|21", "двадцать девять|29", "сто одиннадцать|111",
            "сто двадцать один|121", "две тысячи триста|2300", "одна тысяча одиннадцать|1011",
            "девятьсот девяносто девять тысяч девятьсот девяносто девять|999999"
    })
    void validWordNumbersRemainSupported(String number, String expected) {
        var result = parser.parse("25.09.2026 за день прошла " + number + " шагов");
        assertEquals(PARSED, result.outcome(), result.toString());
        TextInputParserTest.assertMetric(result, "steps", expected, "count");
    }

    @ParameterizedTest @ValueSource(strings = {
            "на обед готовлю пасту 200 г", "на обед заказала пасту 200 г",
            "на обед заказал пасту 200 г", "еда: приготовила пасту 200 г",
            "еда: купил пасту 200 г", "на ужин закажу суп 200 г",
            "на обед паста 200 г"
    })
    void preparationPurchaseAndMenuDoNotBecomeConsumedMeals(String description) {
        var result = parser.parse("25.09.2026 " + description);
        assertNotEquals(PARSED, result.outcome(), result.toString());
        assertNoKnownNumber(result);
        assertTrue(result.data() == null || result.data().type().equals("note"), result.toString());
    }

    @ParameterizedTest @ValueSource(strings = {
            "съела пасту и 200 г супа", "съел пасту и суп 200 г",
            "съела 200 г пасты и суп", "съела пасту плюс 200 г супа",
            "еда: паста 200 г супа", "на обед были паста и суп 200 г"
    })
    void massOfOneDishIsNotAssignedToACombinedMeal(String description) {
        var result = parser.parse("25.09.2026 " + description);
        assertEquals(NEEDS_CLARIFICATION, result.outcome(), result.toString());
        assertNoKnownNumber(result);
    }

    @ParameterizedTest @ValueSource(strings = {
            "еда: паста 200 г/кг", "еда: паста 200 кг/день",
            "съела 200 г / кг пасты", "съел 0,2 кг/сутки пасты",
            "на обед была паста 200 граммов/кг"
    })
    void compoundMassUnitsAreNotReducedToTheirFirstComponent(String description) {
        var result = parser.parse("25.09.2026 " + description);
        assertEquals(NEEDS_CLARIFICATION, result.outcome(), result.toString());
        assertNoKnownNumber(result);
    }

    @ParameterizedTest @CsvSource(delimiter = '|', value = {
            "на обед была паста 200 г|200|reported",
            "на ужин был суп 200 граммов|200|reported",
            "съел 200 г пасты|200|reported",
            "съела 200г пасты|200|reported",
            "еда: паста 0,2 кг|200|computed",
            "съела 0.2 кг пасты|200|computed"
    })
    void unambiguousCompletedMealWithOneMassStillParses(String description, String mass, String origin) {
        var result = parser.parse("25.09.2026 " + description);
        assertEquals(PARSED, result.outcome(), result.toString());
        assertEquals("meal", result.data().type());
        TextInputParserTest.assertNumber(result, "mass_g", mass);
        assertEquals(origin, result.data().fieldOrigins().get("mass_g"));
        assertFalse(result.data().payload().get("description").toString().isBlank());
    }

    private static void assertNoKnownNumber(TextParseResult result) {
        if (result.data() != null) {
            for (String field : new String[]{"value", "mass_g"}) {
                assertFalse(result.data().payload().containsKey(field), result.toString());
                assertFalse(result.data().fieldOrigins().containsKey(field), result.toString());
            }
        }
    }
}
