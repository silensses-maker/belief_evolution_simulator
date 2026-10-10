package utils.rng.distributions

sealed trait Distribution {
    def toString: String
}

case object CustomDistribution extends Distribution {
    override def toString: String = "custom"
}

case object Uniform extends Distribution {
    override def toString: String = "uniform"
}

case class Normal(mean: Double, std: Double) extends Distribution {
    override def toString: String = s"normal(mean=$mean, std=$std)"
}

case class Exponential(lambda: Double) extends Distribution {
    override def toString: String = s"exponential(lambda=$lambda)"
}

case class BiModal(peak1: Double, peak2: Double, lower: Double = 0, upper: Double = 1, stdDev: Option[Double] = None) extends Distribution {
    override def toString: String = s"biModal(peak1=$peak1, peak2=$peak2, lower=$lower, upper=$upper)"
    
    val bimodalDistribution: BimodalDistribution = new BimodalDistribution(peak1, peak2, lower, upper, stdDev)
}

case class RadicalMinority(majorityBelief: Double = 0.5, radicalBelief: Double = 1.0, radicalFraction: Double = 0.1, stdDev: Double = 0.02) extends Distribution {
    override def toString: String = s"radicalMinority(maj=$majorityBelief, rad=$radicalBelief, frac=$radicalFraction)"
}

object Distribution {
    def fromString(s: String): Option[Distribution] = s match {
        case "customDistribution" => Some(CustomDistribution)
        case "uniform" => Some(Uniform)
        case str if str.startsWith("normal") =>
            val params = str.stripPrefix("normal(mean=").stripSuffix(")").split(", std=")
            if (params.length == 2) Some(Normal(params(0).toDouble, params(1).toDouble)) else None
        case str if str.startsWith("exponential") =>
            val param = str.stripPrefix("exponential(lambda=").stripSuffix(")")
            Some(Exponential(param.toDouble))
        case str if str.startsWith("biModal") =>
            Some(BiModal(0.25, 0.75))
        case str if str.startsWith("radicalMinority") =>
            Some(RadicalMinority())
        case _ => None
    }
}
