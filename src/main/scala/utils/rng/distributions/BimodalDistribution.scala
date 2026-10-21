package utils.rng.distributions

import scala.util.Random

class BimodalDistribution(
    peak1: Double, 
    peak2: Double, 
    lower: Double = 0, 
    upper: Double = 1,
    stdDev: Option[Double] = None,
    random: Random = new Random() // pass a seeded generator for reproducible samples
) {
  private val midpoint = (peak1 + peak2) / 2
  private val stdDev1 = stdDev.getOrElse((midpoint - lower) / 3)
  private val stdDev2 = stdDev.getOrElse((upper - midpoint) / 3)

  private def clamp(value: Double, min: Double, max: Double): Double = {
    Math.max(min, Math.min(value, max))
  }

  private def sampleGaussian(peak: Double, sDev: Double): Double = {
    clamp(peak + random.nextGaussian() * sDev, lower, upper)
  }

  def sample(): Double = {
    if (random.nextFloat() < 0.5f) sampleGaussian(peak1, stdDev1)
    else sampleGaussian(peak2, stdDev2)
  }
  
  def sampleFloat(): Float = {
    if (random.nextBoolean()) sampleGaussian(peak1, stdDev1).toFloat
    else sampleGaussian(peak2, stdDev2).toFloat
  }
}
