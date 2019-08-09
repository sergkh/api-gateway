package kamon.play

import _root_.kamon.metric.instrument.UnitOfMeasurement
import _root_.kamon.metric.{CounterKey, GaugeKey, HistogramKey, MinMaxCounterKey}

/**
  *
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  *         created on 03/07/17
  */
object Keys {

  def counterKey(name: String, unitOfMeasurement: UnitOfMeasurement = UnitOfMeasurement.Unknown) =
   CounterKey(name, unitOfMeasurement)

 def histogramKey(name: String, unitOfMeasurement: UnitOfMeasurement = UnitOfMeasurement.Unknown) =
   HistogramKey(name, unitOfMeasurement)

 def gaugeKey(name: String, unitOfMeasurement: UnitOfMeasurement = UnitOfMeasurement.Unknown) =
   GaugeKey(name, unitOfMeasurement)

 def minMaxCounterKey(name: String, unitOfMeasurement: UnitOfMeasurement = UnitOfMeasurement.Unknown) =
   MinMaxCounterKey(name, unitOfMeasurement)

}
