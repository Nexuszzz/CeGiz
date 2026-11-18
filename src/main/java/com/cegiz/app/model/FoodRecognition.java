package com.cegiz.app.model;

import java.util.*;
import org.json.*;

/** Validated photo-specific Gemini estimates. No catalogue lookup or numerical fallback. */
public final class FoodRecognition {
  public final boolean containsFood, uncertain;
  public final String name, estimateNote;
  public final List<String> ingredients;
  private final MealDraft estimate;

  private FoodRecognition(
      boolean food, boolean uncertain, String name, String note, MealDraft meal) {
    containsFood = food;
    this.uncertain = uncertain;
    this.name = name;
    estimateNote = note;
    estimate = meal;
    List<String> names = new ArrayList<>();
    if (meal != null) for (MealDraft.Part p : meal.parts) names.add(p.food.name);
    ingredients = Collections.unmodifiableList(names);
  }

  public MealDraft toDraft() {
    if (!containsFood || estimate == null)
      throw new IllegalStateException("Belum ada estimasi makanan.");
    return estimate.copy();
  }

  public static FoodRecognition parse(String json) throws Exception {
    JSONObject o = new JSONObject(json);
    boolean food = o.getBoolean("contains_food");
    if (!food) return new FoodRecognition(false, true, "", "", null);
    String name = requiredName(o.getString("meal_name"), 80);
    boolean uncertain = o.getBoolean("uncertain");
    JSONArray notes = o.getJSONArray("assumptions");
    if (notes.length() > 4) throw new IllegalArgumentException("Catatan estimasi terlalu panjang.");
    List<String> assumptions = new ArrayList<>();
    for (int i = 0; i < notes.length(); i++) {
      String note = clean(notes.getString(i), 140);
      if (!note.isEmpty()) assumptions.add(note);
    }
    String note = String.join("\n", assumptions);
    MealDraft meal = new MealDraft(name, "ai_food");
    meal.estimateNote = note;
    JSONArray parts = o.getJSONArray("ingredients");
    if (parts.length() < 1 || parts.length() > 12)
      throw new IllegalArgumentException("Bahan belum lengkap.");
    String batch = UUID.randomUUID().toString();
    for (int i = 0; i < parts.length(); i++) {
      JSONObject p = parts.getJSONObject(i);
      String item = requiredName(p.getString("name"), 70);
      double grams = number(p, "estimated_grams", .1, 2000);
      double protein = number(p, "protein_g_per_100g", 0, 100);
      double carbs = number(p, "carbs_g_per_100g", 0, 100);
      double fat = number(p, "fat_g_per_100g", 0, 100);
      double energy = number(p, "energy_kcal_per_100g", 0, 950);
      if (protein + carbs + fat > 100.5 || (energy == 0 && protein + carbs + fat > 1))
        throw new IllegalArgumentException("Nilai gizi belum konsisten.");
      Food ingredient =
          new Food(
              "gemini_" + batch + "_" + i,
              item,
              visual(p.getString("image_key")),
              "Estimasi foto",
              "",
              protein,
              carbs,
              fat,
              100,
              0,
              energy,
              "gemini");
      meal.add(ingredient, grams);
    }
    if (meal.grams() < 5 || meal.grams() > 2000)
      throw new IllegalArgumentException("Estimasi porsi atau gizi belum dapat digunakan.");
    return new FoodRecognition(true, uncertain, name, note, meal);
  }

  private static double number(JSONObject o, String name, double min, double max) throws Exception {
    Object raw = o.get(name);
    if (!(raw instanceof Number))
      throw new IllegalArgumentException("Nilai gizi harus berupa angka.");
    double value = ((Number) raw).doubleValue();
    if (!Double.isFinite(value) || value < min || value > max)
      throw new IllegalArgumentException("Nilai gizi di luar batas.");
    return value;
  }

  private static String requiredName(String value, int max) {
    String result = clean(value, max);
    if (result.isEmpty()) throw new IllegalArgumentException("Nama makanan belum terbaca.");
    return result;
  }

  private static String clean(String value, int max) {
    String result = value.replaceAll("[\\p{Cntrl}]", " ").trim();
    return result.length() > max ? result.substring(0, max) : result;
  }

  // Illustration choices only; nutrient values always come from the response.
  private static String visual(String key) {
    switch (key) {
      case "rice":
        return "food_rice";
      case "chicken":
        return "food_chicken";
      case "vegetable":
        return "food_broccoli";
      case "carrot":
        return "food_carrot";
      case "egg":
        return "food_egg";
      case "tomato":
        return "food_tomato";
      case "oil":
        return "food_oil";
      default:
        return "ai_food";
    }
  }
}
