package kamon.prometheus.converter

import kamon.metric.EntitySnapshot

/** Contains all of the information from an entity snapshot from Kamon.
  *
  * @param category the category name from Kamon
  * @param name the metric name from  Kamon
  * @param tags all of the tags from Kamon
  * @param value the snapshot value of the entity from Kamon
  *
  * @author Daniel Solano Gómez
  */
private[converter] case class MetricSnapshot(category: String, name: String, tags: Map[String,String], value: EntitySnapshot) {
  /** Returns a copy of the snapshot with the category name changed. */
  def withCategory(newCategory: String): MetricSnapshot = copy(category = newCategory)

  /** Returns a copy of the snapshot with the metric name changed. */
  def withName(newName: String): MetricSnapshot = copy(name = newName)

  /** Returns a copy of the snapshot with the tags updated by the given function. */
  def updateTags(f: Map[String,String] ⇒ Map[String,String]): MetricSnapshot = copy(tags = f(tags))

}
