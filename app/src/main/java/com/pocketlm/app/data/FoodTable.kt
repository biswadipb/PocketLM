package com.pocketlm.app.data

import kotlin.math.roundToInt

/**
 * A food with typical values per 100 g, as the usual food tables give them. Real foods differ with brand, ripeness and
 * how they are cooked, so these are for keeping a rough count, not for exact numbers.
 */
data class Food(
    val name: String,
    /** Other names it is known by, for searching. */
    val aliases: String,
    val kcal: Int,
    val proteinG: Float,
    val carbsG: Float,
    val fatG: Float,
    /** A usual portion in grams, and what to call it. */
    val servingG: Int,
    val serving: String,
) {
    /** The values for [grams] of this food, as a log entry for [day]. */
    fun portion(grams: Int, day: Long, id: String = HealthLogs.newId()): FoodEntry {
        val k = grams / 100f
        return FoodEntry(id, day, name, (kcal * k).roundToInt(), round1(proteinG * k), round1(carbsG * k), round1(fatG * k))
    }

    private fun round1(v: Float) = (v * 10).roundToInt() / 10f
}

object FoodTable {

    private fun f(name: String, aliases: String, kcal: Int, p: Float, c: Float, fat: Float, g: Int, serving: String) =
        Food(name, aliases, kcal, p, c, fat, g, serving)

    val all: List<Food> = listOf(
        // Grains and bread
        f("White rice, cooked", "rice chawal", 130, 2.7f, 28f, 0.3f, 150, "1 cup"),
        f("Brown rice, cooked", "rice", 123, 2.7f, 25.6f, 1.0f, 150, "1 cup"),
        f("Roti / chapati (whole wheat)", "chapati phulka", 300, 8f, 50f, 6f, 40, "1 roti"),
        f("Whole wheat bread", "brown bread toast", 250, 12f, 43f, 3.5f, 30, "1 slice"),
        f("White bread", "toast", 265, 9f, 49f, 3.2f, 28, "1 slice"),
        f("Oats, rolled (dry)", "oatmeal porridge", 379, 13.2f, 67.7f, 6.5f, 40, "1/2 cup"),
        f("Pasta, cooked", "spaghetti noodles", 158, 5.8f, 30.9f, 0.9f, 140, "1 cup"),
        f("Quinoa, cooked", "", 120, 4.4f, 21.3f, 1.9f, 150, "1 cup"),
        f("Cornflakes", "cereal", 357, 7.5f, 84f, 0.4f, 30, "1 bowl"),
        f("Idli", "idly", 137, 4f, 29f, 0.5f, 40, "1 idli"),
        f("Dosa, plain", "dosai", 158, 3.9f, 29f, 3f, 85, "1 dosa"),
        f("Poha, cooked", "flattened rice", 129, 2.6f, 23f, 3f, 150, "1 plate"),
        f("Potato, boiled", "aloo", 87, 1.9f, 20f, 0.1f, 150, "1 medium"),
        f("Sweet potato, baked", "shakarkandi", 90, 2f, 20.7f, 0.2f, 150, "1 medium"),
        // Protein
        f("Chicken breast, cooked", "chicken", 165, 31f, 0f, 3.6f, 120, "1 small breast"),
        f("Chicken thigh, cooked, skinless", "chicken", 209, 26f, 0f, 10.9f, 120, "1 thigh"),
        f("Egg, boiled", "anda", 155, 12.6f, 1.1f, 10.6f, 50, "1 large egg"),
        f("Egg white", "", 52, 10.9f, 0.7f, 0.2f, 33, "1 white"),
        f("Salmon, cooked", "fish", 206, 22.1f, 0f, 12.4f, 120, "1 fillet"),
        f("White fish, cooked", "cod fish", 105, 23f, 0f, 0.9f, 120, "1 fillet"),
        f("Tuna, canned in water", "fish", 116, 25.5f, 0f, 0.8f, 100, "1 small can"),
        f("Paneer", "cottage cheese", 265, 18.3f, 1.2f, 20.8f, 50, "2 cubes"),
        f("Tofu, firm", "soya paneer", 144, 15.8f, 3.9f, 8.7f, 100, "1 slice"),
        f("Dal (lentils), cooked", "daal lentil", 116, 9f, 20.1f, 0.4f, 180, "1 bowl"),
        f("Chickpeas, cooked", "chana chole", 164, 8.9f, 27.4f, 2.6f, 150, "1 cup"),
        f("Kidney beans, cooked", "rajma", 127, 8.7f, 22.8f, 0.5f, 150, "1 cup"),
        f("Soya chunks (dry)", "soy nuggets meal maker", 345, 52f, 33f, 0.5f, 30, "1 handful"),
        f("Whey protein powder", "protein shake", 400, 80f, 7f, 6f, 30, "1 scoop"),
        // Dairy
        f("Milk, whole", "doodh", 61, 3.2f, 4.8f, 3.3f, 240, "1 glass"),
        f("Milk, skimmed", "toned milk", 34, 3.4f, 5f, 0.1f, 240, "1 glass"),
        f("Curd / plain yogurt", "dahi yoghurt", 61, 3.5f, 4.7f, 3.3f, 150, "1 bowl"),
        f("Greek yogurt, plain, low fat", "yoghurt", 59, 10.2f, 3.6f, 0.4f, 170, "1 pot"),
        f("Cheddar cheese", "cheese", 403, 24.9f, 1.3f, 33.1f, 28, "1 slice"),
        // Fats and nuts
        f("Butter", "makhan", 717, 0.9f, 0.1f, 81f, 10, "2 tsp"),
        f("Ghee", "clarified butter", 900, 0f, 0f, 100f, 10, "2 tsp"),
        f("Cooking oil", "olive oil vegetable oil", 884, 0f, 0f, 100f, 10, "2 tsp"),
        f("Peanut butter", "", 588, 25f, 20f, 50f, 32, "2 tbsp"),
        f("Almonds", "badam nuts", 579, 21.2f, 21.6f, 49.9f, 28, "a handful"),
        f("Cashews", "kaju nuts", 553, 18.2f, 30.2f, 43.9f, 28, "a handful"),
        f("Peanuts, roasted", "moongphali groundnut", 585, 23.7f, 21.5f, 49.7f, 28, "a handful"),
        f("Avocado", "", 160, 2f, 8.5f, 14.7f, 100, "half"),
        f("Hummus", "", 166, 7.9f, 14.3f, 9.6f, 30, "2 tbsp"),
        // Fruit and vegetables
        f("Banana", "kela", 89, 1.1f, 22.8f, 0.3f, 118, "1 medium"),
        f("Apple", "seb", 52, 0.3f, 13.8f, 0.2f, 180, "1 medium"),
        f("Orange", "santra", 47, 0.9f, 11.8f, 0.1f, 130, "1 medium"),
        f("Mango", "aam", 60, 0.8f, 15f, 0.4f, 165, "1 cup"),
        f("Grapes", "angoor", 69, 0.7f, 18.1f, 0.2f, 150, "1 cup"),
        f("Papaya", "papita", 43, 0.5f, 11f, 0.3f, 150, "1 cup"),
        f("Watermelon", "tarbooz", 30, 0.6f, 7.6f, 0.2f, 150, "1 cup"),
        f("Broccoli, cooked", "", 35, 2.4f, 7.2f, 0.4f, 90, "1 cup"),
        f("Spinach, cooked", "palak", 23, 3f, 3.8f, 0.3f, 100, "1/2 cup"),
        f("Cauliflower, cooked", "gobi", 23, 1.8f, 4.1f, 0.5f, 100, "1/2 cup"),
        f("Carrot, raw", "gajar", 41, 0.9f, 9.6f, 0.2f, 60, "1 medium"),
        f("Tomato", "tamatar", 18, 0.9f, 3.9f, 0.2f, 120, "1 medium"),
        f("Cucumber", "kheera", 15, 0.7f, 3.6f, 0.1f, 100, "1 cup"),
        f("Onion", "pyaz", 40, 1.1f, 9.3f, 0.1f, 100, "1 medium"),
        // Snacks, drinks and sweet things
        f("Samosa", "fried snack", 258, 5f, 28f, 14f, 100, "1 samosa"),
        f("Pizza, cheese", "pizza slice", 266, 11f, 33f, 10f, 107, "1 slice"),
        f("French fries", "chips", 313, 3.4f, 41f, 15f, 100, "1 portion"),
        f("Dark chocolate", "chocolate", 598, 7.8f, 45.9f, 42.6f, 20, "2 squares"),
        f("Sugar", "cheeni", 387, 0f, 100f, 0f, 4, "1 tsp"),
        f("Honey", "shahad", 304, 0.3f, 82.4f, 0f, 21, "1 tbsp"),
        f("Cola", "soft drink soda", 42, 0f, 10.6f, 0f, 330, "1 can"),
        f("Orange juice", "juice", 45, 0.7f, 10.4f, 0.2f, 240, "1 glass"),
        f("Coffee, black", "", 1, 0.1f, 0f, 0f, 240, "1 cup"),
    )

    /** The foods whose name or other names begin with a word of [query], then those that contain it; at most [limit]. */
    fun search(query: String, limit: Int = 8): List<Food> {
        val words = query.lowercase().split(' ', ',').filter { it.length >= 2 }
        if (words.isEmpty()) return emptyList()
        fun text(food: Food) = (food.name + " " + food.aliases).lowercase()
        val matching = all.filter { food -> words.all { it in text(food) } }
        val starts = matching.filter { food -> text(food).split(' ', ',', '/', '(', ')').any { part -> words.any { part.startsWith(it) } } }
        return (starts + matching).distinct().take(limit)
    }
}
