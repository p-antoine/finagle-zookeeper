package com.twitter.finagle.exp.zookeeper.client

import com.twitter.finagle.exp.zookeeper.ZookeeperDefs.OpCode
import com.twitter.finagle.exp.zookeeper._
import com.twitter.finagle.exp.zookeeper.connection.{Connection, ConnectionManager}
import com.twitter.finagle.exp.zookeeper.data.{ACL, Auth}
import com.twitter.finagle.exp.zookeeper.session.Session.States
import com.twitter.finagle.exp.zookeeper.session.{Session, SessionManager}
import com.twitter.finagle.exp.zookeeper.utils.PathUtils._
import com.twitter.finagle.exp.zookeeper.watch.{WatchManager, WatchType}
import com.twitter.logging.Logger
import com.twitter.util._

class ZkClient(
  hostList: String,
  readOnly: Boolean = false,
  chroot: Option[String] = None,
  autoReconnect: Boolean = true
  ) extends Closable {


  private[this] val connectionManager = new ConnectionManager(hostList)
  private[this] val sessionManager = new SessionManager(autoReconnect, this)
  @volatile private[this] var connection: Connection = {
    connectionManager.initConnection()
    connectionManager.connection
  }
  @volatile private[this] var session: Session = sessionManager.session
  private[finagle] val watchManager: WatchManager = new WatchManager(chroot)

  //TODO implement request to the dispatcher (getState, getSessionID, getPassword

  def addAuth(auth: Auth): Future[Unit] = {
    // TODO check auth ?
    val rep = ReqPacket(
      Some(RequestHeader(-4, OpCode.AUTH)),
      Some(new AuthRequest(0, auth)))

    connection.serve(rep) flatMap { rep =>
      session.parseStateHeader(rep.header)
      if (rep.header.err == 0) {
        Future.Unit
      } else {
        Future.exception(ZookeeperException.create("Error while addAuth", rep.header.err))
      }
    }
  }

  def addAuth(scheme: String, data: Array[Byte]): Future[Unit] = {
    // TODO check auth ?
    val rep = ReqPacket(
      Some(RequestHeader(-4, OpCode.AUTH)),
      Some(new AuthRequest(0, Auth(scheme, data))))

    connection.serve(rep) flatMap { rep =>
      session.parseStateHeader(rep.header)
      if (rep.header.err == 0) {
        Future.Unit
      } else {
        Future.exception(ZookeeperException.create("Error while addAuth", rep.header.err))
      }
    }
  }

  def connect(timeOut: Int = 2000): Future[ConnectResponse] = {
    session.canConnect
    session.state = States.CONNECTING

    val rep = ReqPacket(None, Some(new ConnectRequest(0, 0L, timeOut)))

    connection.serve(rep) flatMap { rep =>
      session.parseStateHeader(rep.header)
      Future(rep.response.get.asInstanceOf[ConnectResponse])
    } onSuccess { rep =>
      sessionManager.initSession(rep)
      session = sessionManager.session
      // gives the current session to the dispatcher
      configureSession(session)
      // gives the watchManger to the dispatcher
      configureWatchManager(watchManager)
      session.startPing(ping())
    } onFailure { exc =>
      session.state = States.CLOSED
      Future.exception(exc)
    }
  }

  def close(deadline: Time): Future[Unit] = connection.close(deadline)
  def closeService(): Future[Unit] = connection.close()
  def closeSession(): Future[Unit] = {
    checkState()
    session.prepareClose()
    // fixme manage if close session failed

    val rep = ReqPacket(Some(RequestHeader(1, OpCode.CLOSE_SESSION)), None)

    connection.serve(rep) flatMap { rep =>
      session.parseStateHeader(rep.header)
      Future.Unit
    } onSuccess (_ => session.close())
  }

  private[this] def configureSession(session: Session): Future[Unit] = {

    val req = ReqPacket(None, Some(ConfigureRequest(Right(session))))
    connection.serve(req).unit

  }
  private[this] def configureWatchManager(manager: WatchManager): Future[Unit] = {

    val req = ReqPacket(None, Some(ConfigureRequest(Left(manager))))
    connection.serve(req).unit

  }

  def create(
    path: String,
    data: Array[Byte],
    acl: Array[ACL],
    createMode: Int): Future[String] = {

    val finalPath = prependChroot(path, chroot)
    validatePath(finalPath, createMode)
    ACL.check(acl)
    val req = ReqPacket(
      Some(RequestHeader(session.getXid, OpCode.CREATE)),
      Some(CreateRequest(finalPath, data, acl, createMode)))

    connection.serve(req) flatMap { rep =>
      session.parseStateHeader(rep.header)

      if (rep.header.err == 0) {
        session.parseStateHeader(rep.header)
        val finalRep = rep.response.get.asInstanceOf[CreateResponse]
        Future(finalRep.path.substring(chroot.getOrElse("").length))
      } else {
        Future.exception(ZookeeperException.create("Error while create", rep.header.err))
      }
    }
  }

  def delete(path: String, version: Int): Future[Unit] = {

    val finalPath = prependChroot(path, chroot)
    validatePath(finalPath)
    val req = ReqPacket(
      Some(RequestHeader(session.getXid, OpCode.DELETE)),
      Some(DeleteRequest(finalPath, version))
    )

    connection.serve(req) flatMap { rep =>
      session.parseStateHeader(rep.header)

      if (rep.header.err == 0) {
        session.parseStateHeader(rep.header)
        Future.Unit
      } else {
        Future.exception(ZookeeperException.create("Error while getACL", rep.header.err))
      }
    }
  }

  def exists(path: String, watch: Boolean = false): Future[ExistsResponse] = {

    val finalPath = prependChroot(path, chroot)
    validatePath(finalPath)
    val req = ReqPacket(
      Some(RequestHeader(session.getXid, OpCode.EXISTS)),
      Some(ExistsRequest(finalPath, watch)))

    connection.serve(req) flatMap { rep =>
      session.parseStateHeader(rep.header)

      rep.response match {
        case Some(response: NodeWithWatch) =>
          if (watch) {
            val watch = watchManager.register(path, WatchType.exists)
            val rep = NodeWithWatch(response.stat, Some(watch))
            Future(rep)
          } else {
            Future(response)
          }
        case None =>
          if (rep.header.err == -101 && watch) {
            val watch = watchManager.register(path, WatchType.exists)
            Future(NoNodeWatch(watch))
          } else {
            Future.exception(ZookeeperException.create("Error while exists", rep.header.err))
          }
      }
    }
  }


  def getACL(path: String): Future[GetACLResponse] = {

    val finalPath = prependChroot(path, chroot)
    validatePath(finalPath)
    val req = ReqPacket(
      Some(RequestHeader(session.getXid, OpCode.GET_ACL)),
      Some(GetACLRequest(finalPath)))

    connection.serve(req) flatMap { rep =>
      session.parseStateHeader(rep.header)

      if (rep.header.err == 0) {
        Future(rep.response.get.asInstanceOf[GetACLResponse])
      } else {
        Future.exception(ZookeeperException.create("Error while getACL", rep.header.err))
      }
    }
  }

  def getChildren(path: String, watch: Boolean = false): Future[GetChildrenResponse] = {

    val finalPath = prependChroot(path, chroot)
    validatePath(finalPath)
    val req = ReqPacket(
      Some(RequestHeader(session.getXid, OpCode.GET_CHILDREN)),
      Some(GetChildrenRequest(finalPath, watch)))

    connection.serve(req) flatMap { rep =>
      session.parseStateHeader(rep.header)

      if (rep.header.err == 0) {
        val res = rep.response.get.asInstanceOf[GetChildrenResponse]
        if (watch) {
          val watch = watchManager.register(path, WatchType.exists)
          val childrenList = res.children map (_.substring(chroot.getOrElse("").length))
          val rep = GetChildrenResponse(childrenList, Some(watch))
          Future(rep)
        } else {
          val childrenList = res.children map (_.substring(chroot.getOrElse("").length))
          val rep = GetChildrenResponse(childrenList, None)
          Future(rep)
        }
      } else {
        Future.exception(ZookeeperException.create("Error while getChildren", rep.header.err))
      }
    }
  }

  def getChildren2(path: String, watch: Boolean = false): Future[GetChildren2Response] = {

    val finalPath = prependChroot(path, chroot)
    validatePath(finalPath)
    val req = ReqPacket(
      Some(RequestHeader(session.getXid, OpCode.GET_CHILDREN2)),
      Some(GetChildren2Request(finalPath, watch)))

    connection.serve(req) flatMap { rep =>
      session.parseStateHeader(rep.header)

      if (rep.header.err == 0) {
        val res = rep.response.get.asInstanceOf[GetChildren2Response]
        if (watch) {
          val watch = watchManager.register(path, WatchType.exists)
          val childrenList = res.children map (_.substring(chroot.getOrElse("").length))
          val rep = GetChildren2Response(childrenList, res.stat, Some(watch))
          Future(rep)
        } else {
          val childrenList = res.children map (_.substring(chroot.getOrElse("").length))
          val rep = GetChildren2Response(childrenList, res.stat, None)
          Future(rep)
        }
      } else {
        Future.exception(ZookeeperException.create("Error while getChildren2", rep.header.err))
      }
    }
  }

  def getData(path: String, watch: Boolean = false): Future[GetDataResponse] = {

    val finalPath = prependChroot(path, chroot)
    validatePath(finalPath)
    val req = ReqPacket(
      Some(RequestHeader(session.getXid, OpCode.GET_DATA)),
      Some(GetDataRequest(finalPath, watch)))

    connection.serve(req) flatMap { rep =>
      session.parseStateHeader(rep.header)

      if (rep.header.err == 0) {
        val res = rep.response.get.asInstanceOf[GetDataResponse]
        if (watch) {
          val watch = watchManager.register(path, WatchType.exists)
          val rep = GetDataResponse(res.data, res.stat, Some(watch))
          Future(rep)
        } else {
          Future(res)
        }
      } else {
        Future.exception(ZookeeperException.create("Error while getData", rep.header.err))
      }
    }
  }

  // GetMaxChildren is implemented but not available in the java lib
  /*def getMaxChildren(path: String, xid: Int): Future[Response] = {
    /*PathUtils.validatePath(path, createMode)
    val finalPath = PathUtils.prependChroot(path, null)*/
    println("<--getMaxChildren: " + xid)

    val header = RequestHeader(xid, ?)
    val body = GetDataRequestBody(path, false) // false because watch's not supported

    connectionManager.serve(new GetDataRequest(header, body))
  }*/

  private[this] def ping(): Future[Unit] = {
    checkState()

    val req = ReqPacket(Some(RequestHeader(-2, OpCode.PING)), None)

    connection.serve(req) flatMap { rep =>
      session.parseStateHeader(rep.header)
      Future.Unit
    }
  }

  def setACL(path: String, acl: Array[ACL], version: Int): Future[SetACLResponse] = {

    val finalPath = prependChroot(path, chroot)
    validatePath(finalPath)
    ACL.check(acl)
    val req = ReqPacket(
      Some(RequestHeader(session.getXid, OpCode.SET_ACL)),
      Some(SetACLRequest(finalPath, acl, version)))

    connection.serve(req) flatMap { rep =>
      session.parseStateHeader(rep.header)

      if (rep.header.err == 0) {
        val res = rep.response.get.asInstanceOf[SetACLResponse]
        Future(res)
      } else {
        Future.exception(ZookeeperException.create("Error while setACL", rep.header.err))
      }
    }
  }

  def setData(path: String, data: Array[Byte], version: Int): Future[SetDataResponse] = {
    val finalPath = prependChroot(path, chroot)
    validatePath(finalPath)
    val req = ReqPacket(
      Some(RequestHeader(session.getXid, OpCode.SET_DATA)),
      Some(SetDataRequest(finalPath, data, version)))

    connection.serve(req) flatMap { rep =>
      session.parseStateHeader(rep.header)

      if (rep.header.err == 0) {
        val res = rep.response.get.asInstanceOf[SetDataResponse]
        Future(res)
      } else {
        Future.exception(ZookeeperException.create("Error while setData", rep.header.err))
      }
    }
  }

  // We can only use this on reconnection
  private[this] def setWatches(relativeZxid: Int,
    dataWatches: Array[String],
    existsWatches: Array[String],
    childWatches: Array[String]
    ): Future[Unit] = {
    val req = ReqPacket(
      Some(RequestHeader(-8, OpCode.SET_WATCHES)),
      Some(SetWatchesRequest(relativeZxid, dataWatches, existsWatches, childWatches)))

    // fixme chroot all paths
    // fixme add watches in watchManager if request succeed
    connection.serve(req) flatMap { rep =>
      session.parseStateHeader(rep.header)

      if (rep.header.err == 0) {
        Future.Unit
      } else {
        Future.exception(ZookeeperException.create("Error while setWatches", rep.header.err))
      }
    }
  }

  def sync(path: String): Future[SyncResponse] = {

    val finalPath = prependChroot(path, chroot)
    validatePath(finalPath)
    val req = ReqPacket(
      Some(RequestHeader(session.getXid, OpCode.SYNC)),
      Some(SyncRequest(finalPath)))

    connection.serve(req) flatMap { rep =>
      session.parseStateHeader(rep.header)

      if (rep.header.err == 0) {
        val res = rep.response.get.asInstanceOf[SyncResponse]
        val finalRep = SyncResponse(res.path.substring(chroot.getOrElse("").length))
        Future(finalRep)
      } else {
        Future.exception(ZookeeperException.create("Error while sync", rep.header.err))
      }
    }
  }

  def transaction(opList: Array[OpRequest]): Future[TransactionResponse] = {

    Transaction.prepareAndCheck(opList, chroot) match {
      case Return(res) =>
        val transaction = new Transaction(res)
        val req = ReqPacket(
          Some(RequestHeader(session.getXid, OpCode.MULTI)),
          Some(new TransactionRequest(transaction)))

        connection.serve(req) flatMap { rep =>
          session.parseStateHeader(rep.header)

          if (rep.header.err == 0) {
            val res = rep.response.get.asInstanceOf[TransactionResponse]
            val finalOpList = Transaction.formatPath(res.responseList, chroot)
            Future(TransactionResponse(finalOpList))
          } else {
            Future.exception(ZookeeperException.create("Error while transaction", rep.header.err))
          }
        }
      case Throw(exc) => Future.exception(exc)
    }
  }


  /**
   * This method is called each time we try to write on the Transport
   * to make sure the connection is still alive. If it's not then it can
   * try to reconnect ( if the session has not expired ) or create a new session
   * if the session has expired. It won't connect if the client has never connected
   */
  def checkState() {
    if (!session.isFirstConnect) {
      if (session.state == States.CONNECTION_LOST) {
        // We can try to reconnect with last zxid and set the watches back
        session.pingScheduler.currentTask.get.cancel()
        //reconnect

      } else if (session.state == States.SESSION_MOVED) {
        // The session has moved to another server
        // TODO what ?

      } else if (session.state == States.SESSION_EXPIRED) {
        // Reconnect with a new session
        session.pingScheduler.currentTask.get.cancel()
        //connect

      } else if (session.state != States.CONNECTED) {
        // TRY to reconnect with a new session
        throw new RuntimeException("Client is not connected, see SessionManager")
      }
    } else {
      // TODO this is false while pinging RW server
      if (session.state != States.CONNECTING)
        throw new RuntimeException("No connection exception: Did you ever connected to the server ? " + session.state)
    }
  }


}

object ZkClient {
  private[this] val logger = Logger("Finagle-zookeeper")
  def getLogger = logger

  def apply(hostList: String): ZkClient = {
    new ZkClient(hostList)
  }
}