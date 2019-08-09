API Gateway
==============================

Main purpose of this project is to create an API Gateway that provides authorization and users/permissions 
management for any web generic service.

Connection of a new service is done dynamically using [Swagger](http://swagger.io/). 
Service must expose an URL with a swagger JSON (or it can be stored externally) and the API Gateway will add them into the 
common swagger (`/docs/api.html`) and will proxy requests to it. For authorized users proxied requests contain a header with 
a JWT token containing user information and signed by a secret provided with service configuration.

## Authorization header

If a request is performed by an authorized user the API Gateway automatically adds a `X-Auth` header with an user 
information in a JWT token. The JSON format of user data has the following format:

```json
{ 
  "uuid": 2134443645645546,
  "email" : "email@user.com",
  "phone": "+440989452367",
  "firstName": "John",
  "lastName": "Jonson",
  "flags" : [ "ANY_STRING", "ADMIN", "BLOCKED" ],
  "permissions": ["users:edit", "world:conquer", "settings:break"]
}
```

Among them only `uuid` field is mandatory, all other fields should be considered as optional. There is also a shorter form
of user data JSON that can be optionally enabled on the API Gateway:

```json
{
  "id": 2134443645645546,
  "em" : "email@user.com",
  "ph": "+440989452367",
  "fn": "John",
  "ln": "Jonson",
  "rol": [ "ADMIN", "TEST"],
  "flg" : [ "ANY_STRING", "ADMIN", "BLOCKED" ],
  "prm": ["users:edit", "world:conquer", "settings:break"]
}
```

## Dynamic services discovery

Dynamic services discovery is intended to be used in a Docker Swarm environment and allows to automatically discover available 
docker swarm services. For dynamic services discovery API Gateway uses a [ETCD](https://coreos.com/etcd/docs/latest/) in 
combination with [Docker ETCD registrator](https://github.com/sergkh/docker-etcd-registrator). Both of them can be installed as
docker services. By default API Gateway will search etcd on the following endpoint `http://etcd:2379`, so docker service
for etcd should be called etcd, but this can be configured using env variable `ETCD_URL`. 

All additional service configuration for API Gateway can be done by service labels or env variables: 

* **Secret**: secret key to be used to sign the JWT token with user can be specified using `secret` label or 
  `SECRET`/`PLAY_SECRET` env variables. If not specified API gateway will use own secret.
* **Base path**: If base path is different from a root, one can use a label `base_path=/newroot/` to specify it.  
* **Prefix**: If URLs have to be additionally prefixed on a API Gateway use a `prefix=/subservice` label.
* **Swagger URL**: Can be specified using a `swagger` label, otherwise API gateway will expect swagger at a relative URL `/docs/api.json`.
* **Skip service**: To skip a service from discovery add a `skippable = true` label. 

Docker ETCD registrator is used automatically to automatically add/remove services into ETCD so they can be fetched by 
API Gateway. It adds all service labels and environment variables into service description on ETCD. It has to be run on the 
Docker swarm manager node and mainly it is used to decompose API Gateway from Docker. Example key in the etcd:

```json
{
  "action":"get",
  "node": {
    "key": "/services",
    "dir": true,
    "nodes": [
      {"key":"/services/registrator","value":"{\"name\": \"registrator\", \"Env\": [\"ETCD_HOST=etcd\", \"ETCD_PORT=2379\"]}","modifiedIndex":4,"createdIndex":4},
      {"key":"/services/etcd","value":"{\"address\": \"etcd:2380\", \"name\": \"etcd\"}","modifiedIndex":5,"createdIndex":5},
      {"key":"/services/bouncer","value":"{\"address\": \"bouncer:9000\", \"name\": \"bouncer\", \"Env\": [\"JAVA_OPTS=-Xmx1g\"]}","modifiedIndex":9,"createdIndex":9},
      {"key":"/services/reports","value":"{\"address\": \"reports:9000\", \"name\": \"reports\"}","modifiedIndex":10,"createdIndex":10}
    ],
    "modifiedIndex": 4,
    "createdIndex": 4
  }
}
```


## Manual service configuration

Services can be also added manually into the application config using `services` key:

```hocon

services = [
  {
    name = "reports"
    secret = "qwertyuiopqwertyuiopqwertyuiop"
    address = "http://192.168.1.1:9001"
    swagger = "http://192.168.1.1:9001/docs/api.json" #optional
    prefix = "/reports" #optional
  }
]

```
  
