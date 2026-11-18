package com.cegiz.app.model;

import java.util.ArrayList;
import java.util.List;

public final class MealDraft {
  public static final class Part {
    public Food food;
    public double grams;

    public Part(Food food, double grams) {
      this.food = food;
      this.grams = grams;
    }
  }

  public final List<Part> parts = new ArrayList<>();
  public String name, image;
  public String estimateNote = "";

  public MealDraft(String name, String image) {
    this.name = name;
    this.image = image;
  }

  public MealDraft add(Food f, double grams) {
    parts.add(new Part(f, grams));
    return this;
  }

  public double grams() {
    double n = 0;
    for (Part p : parts) n += p.grams;
    return n;
  }

  public double protein() {
    double n = 0;
    for (Part p : parts) n += p.food.protein * p.grams / 100;
    return n;
  }

  public double carbs() {
    double n = 0;
    for (Part p : parts) n += p.food.carbs * p.grams / 100;
    return n;
  }

  public double fat() {
    double n = 0;
    for (Part p : parts) n += p.food.fat * p.grams / 100;
    return n;
  }

  public double energy() {
    double n = 0;
    for (Part p : parts) n += p.food.energy() * p.grams / 100;
    return n;
  }

  public String nutritionSource() {
    String source = null;
    for (Part p : parts) {
      if (source == null) source = p.food.nutritionSource;
      else if (!source.equals(p.food.nutritionSource)) return "mixed";
    }
    return source == null ? "catalogue" : source;
  }

  public boolean isGeminiEstimate() {
    return "gemini".equals(nutritionSource());
  }

  public void scaleTo(double grams) {
    if (!Double.isFinite(grams) || grams < 5 || grams > 2000)
      throw new IllegalArgumentException("Porsi harus 5–2.000 g");
    double old = grams();
    if (old <= 0) throw new IllegalStateException("Bahan belum ada");
    for (Part p : parts) p.grams = p.grams * grams / old;
  }

  public MealDraft copy() {
    MealDraft d = new MealDraft(name, image);
    d.estimateNote = estimateNote;
    for (Part p : parts) d.add(p.food, p.grams);
    return d;
  }
}
