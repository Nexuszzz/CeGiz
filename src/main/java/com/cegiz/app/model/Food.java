package com.cegiz.app.model;

/** Nutrition per 100 g with an explicit origin. */
public final class Food {
  public final String id, name, image, category, allergens;
  public final double protein, carbs, fat;
  public final double kcalPer100;
  public final String nutritionSource;
  public final int portion, minutes;

  public Food(
      String id,
      String name,
      String image,
      String category,
      String allergens,
      double p,
      double c,
      double f,
      int portion,
      int minutes) {
    this(
        id,
        name,
        image,
        category,
        allergens,
        p,
        c,
        f,
        portion,
        minutes,
        p * 4 + c * 4 + f * 9,
        "catalogue");
  }

  public Food(
      String id,
      String name,
      String image,
      String category,
      String allergens,
      double p,
      double c,
      double f,
      int portion,
      int minutes,
      double kcalPer100,
      String source) {
    this.id = id;
    this.name = name;
    this.image = image;
    this.category = category;
    this.allergens = allergens;
    this.protein = p;
    this.carbs = c;
    this.fat = f;
    this.portion = portion;
    this.minutes = minutes;
    this.kcalPer100 = kcalPer100;
    this.nutritionSource = source;
  }

  public double energy() {
    return kcalPer100;
  }
}
