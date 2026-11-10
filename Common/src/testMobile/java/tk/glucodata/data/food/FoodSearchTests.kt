package tk.glucodata.data.food

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodSearchTests {
    private fun search(names: List<String>, query: String, limit: Int = 10): List<String> =
        FoodSearch.search(names.map { FoodSearch.IndexedName(it) }, query, limit) { it }.map { it.name }

    @Test
    fun everyTypedWordMustStartAWordOfTheName() {
        val names = listOf("Potato, boiled", "Potato chips", "Sweet potato, baked", "Tomato")
        assertEquals(listOf("Potato, boiled"), search(names, "boil pot"))
        assertEquals(setOf("Potato, boiled", "Potato chips", "Sweet potato, baked"), search(names, "pota").toSet())
    }

    @Test
    fun aNameThatStartsWithTheQueryComesFirst() {
        val names = listOf("Pineapple", "Apple pie", "Apple", "Applesauce, unsweetened")
        assertEquals(listOf("Apple", "Apple pie", "Applesauce, unsweetened", "Pineapple"), search(names, "app"))
    }

    @Test
    fun accentsAndCaseAreIgnored() {
        assertEquals(listOf("Crème fraîche"), search(listOf("Crème fraîche"), "creme fra"))
        assertEquals(listOf("Äpple"), search(listOf("Äpple"), "apple"))
        assertEquals(listOf("Żurawina"), search(listOf("Żurawina"), "zur"))
        assertEquals(listOf("Weißbrot"), search(listOf("Weißbrot"), "weissbrot"))
    }

    @Test
    fun cyrillicIsMatchedTheWayItIsTyped() {
        val names = listOf("Гречка отварная", "Грейпфрут", "Свёкла отварная", "Йогурт натуральный", "Чай")
        assertEquals("Гречка отварная", search(names, "греч").single())
        assertEquals(listOf("Свёкла отварная"), search(names, "свекла"))
        // й is a letter, not an accented и.
        assertEquals(listOf("Йогурт натуральный"), search(names, "йог"))
        assertTrue(search(names, "иог").isEmpty())
    }

    @Test
    fun aNameWithoutWordBreaksMatchesFromItsStart() {
        assertEquals(listOf("苹果", "苹果汁"), search(listOf("苹果汁", "苹果", "橙汁"), "苹果"))
    }

    @Test
    fun nothingTypedFindsNothing() {
        assertTrue(search(listOf("Apple"), "  ").isEmpty())
        assertNull(FoodSearch.rank(FoodSearch.IndexedName("Apple"), emptyList(), ""))
    }

    @Test
    fun theLimitKeepsTheBest() {
        assertEquals(listOf("Rice"), search(listOf("Fried rice", "Rice cake", "Rice"), "rice", limit = 1))
    }
    @Test
    fun punctuationDoesNotHideAnExactNameAndFrequencyBreaksEqualRanks() {
        val exact = FoodSearch.IndexedName("Fish & chips")
        assertEquals(0, FoodSearch.rank(exact, FoodSearch.words("fish & chips"), "fish chips"))
        val short = FoodSearch.IndexedName("Apple, red") to 1
        val frequent = FoodSearch.IndexedName("Apple, red and green") to 20
        val found = FoodSearch.search(listOf(short, frequent), "apple", 2, preference = { it.second }) { it.first }
        assertEquals(frequent, found.first())
    }

}
