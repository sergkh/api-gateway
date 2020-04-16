package events
import models.Service

case class ServiceDiscovered(service: Service) extends AppEvent
case class ServiceLost(service: Service) extends AppEvent
case class ServicesListUpdate(services: Seq[Service]) extends AppEvent
