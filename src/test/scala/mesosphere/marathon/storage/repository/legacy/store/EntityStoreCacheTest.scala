package mesosphere.marathon.storage.repository.legacy.store

import mesosphere.marathon.Protos
import mesosphere.marathon.Protos.MarathonApp
import mesosphere.marathon.state.{ MarathonState, Timestamp }
import mesosphere.marathon.test.{ MarathonSpec, Mockito }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfter, GivenWhenThen, Matchers }

import scala.collection.concurrent.TrieMap
import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.concurrent.Future

class EntityStoreCacheTest extends MarathonSpec with GivenWhenThen with Matchers with BeforeAndAfter with ScalaFutures with Mockito {

  test("The preDriverStarts trigger fills the cache") {
    Given("A store with some entries")
    val names = Set("a", "b", "c")
    content ++= names.map(t => t -> TestApp(t))

    When("On elected is called on the cache")
    entityCache.preDriverStarts.futureValue

    Then("All values are cached")
    entityCache.cacheOpt should not be empty
    entityCache.cacheOpt.get should have size 3
    entityCache.cacheOpt.get.keySet should be(names)
  }

  test("The postDriverTerminates trigger will clear the state") {
    Given("A pre-filled entityCache")
    val names = Set("a", "b", "c")
    entityCache.cacheOpt = Some(new TrieMap[String, Option[TestApp]]())
    entityCache.cacheOpt.get ++= names.map(t => t -> Some(TestApp(t)))

    When("On defeated is called on the cache")
    entityCache.postDriverTerminates.futureValue

    Then("All values are removed")
    entityCache.cacheOpt should be(empty)
  }

  test("Fetching an entry will succeed without querying store in cached mode") {
    Given("A pre-filled entityCache")
    val store = mock[EntityStore[TestApp]]
    store.isOpen returns true
    entityCache = new EntityStoreCache[TestApp](store)
    entityCache.markOpen()
    val names = Set("a", "b", "c")
    entityCache.cacheOpt = Some(new TrieMap[String, Option[TestApp]]())
    entityCache.cacheOpt.get ++= names.map(t => t -> Some(TestApp(t)))

    When("Fetch an existing entry from the cache")
    val a = entityCache.fetch("a").futureValue

    Then("The value is returned without interaction to the underlying store")
    a should be(Some(TestApp("a")))
    verify(store, never).fetch(any)

    And("Fetch an existing entry with version from the cache")
    val aWithVersion = entityCache.fetch("a:1970-01-01T00:00:00.000Z").futureValue

    Then("The value is returned without interaction to the underlying store")
    aWithVersion should be(Some(TestApp("a")))
    verify(store, never).fetch(any)

    And("Fetch a non existing entry from the cache")
    val notExisting = entityCache.fetch("notExisting").futureValue

    Then("No value is returned without interaction to the underlying store")
    notExisting should be(None)
    verify(store, never).fetch(any)
  }

  test("Fetching an unversioned entry will succeed with querying store in direct mode") {
    Given("A UNfilled entityCache")
    val store = mock[EntityStore[TestApp]]
    store.isOpen returns true
    store.fetch("a") returns Future.successful(Some(TestApp("a")))
    entityCache = new EntityStoreCache[TestApp](store)
    entityCache.markOpen()

    When("Fetch an existing entry from the cache")
    val a = entityCache.fetch("a").futureValue

    Then("The value is returned by querying the store")
    a should be(Some(TestApp("a")))
    verify(store).markOpen()
    verify(store).isOpen
    verify(store).fetch("a")
    noMoreInteractions(store)
  }

  test("Fetching an unknown unversioned entry will succeed with querying store in direct mode") {
    Given("A UNfilled entityCache")
    val store = mock[EntityStore[TestApp]]
    store.isOpen returns true
    store.fetch("notExisting") returns Future.successful(None)
    entityCache = new EntityStoreCache[TestApp](store)
    entityCache.markOpen()

    When("Fetch an unknown entry from store")
    val notExisting = entityCache.fetch("notExisting").futureValue

    Then("No value is returned after querying the store")
    notExisting should be(None)
    verify(store).markOpen()
    verify(store).isOpen
    verify(store).fetch("notExisting")
    noMoreInteractions(store)
  }

  test("Fetching a versioned entry will succeed with querying store in direct mode") {
    Given("A UNfilled entityCache")
    val store = mock[EntityStore[TestApp]]
    store.isOpen returns true
    store.fetch("b:1970-01-01T00:00:00.000Z") returns Future.successful(Some(TestApp("b")))
    entityCache = new EntityStoreCache[TestApp](store)

    When("Fetching an existing entry with version")
    val aWithVersion = entityCache.fetch("b:1970-01-01T00:00:00.000Z").futureValue

    Then("The value is returned by querying the store")
    aWithVersion should be(Some(TestApp("b")))
    verify(store).isOpen
    verify(store).fetch("b:1970-01-01T00:00:00.000Z")
    noMoreInteractions(store)
  }

  test("Fetching a versioned entry will delegate to the store in cache mode") {
    Given("A pre-filled entityCache")
    val names = Set("a", "b", "c")
    val fixed = Timestamp.now()
    content ++= names.map(t => s"$t:$fixed" -> TestApp(t, fixed))
    entityCache.preDriverStarts.futureValue

    When("A known versioned entry is fetched")
    val a = entityCache.fetch(s"a:$fixed").futureValue

    Then("The versioned entry is read from cache")
    a should be (Some(TestApp("a", fixed)))

    And("An unknown versioned entry is fetched")
    val unknown = entityCache.fetch(s"a:${Timestamp.zero}").futureValue

    Then("The store is used to fetch that version")
    unknown should be (None)
  }

  test("Modify will delegate to store in direct mode") {
    Given("A store")

    When("Modify an entity")
    val update = TestApp("a", Timestamp.now())
    val a = entityCache.modify("a")(_ => update).futureValue

    Then("Modify on the store will be called")
    content.keys should be (Set("a"))
    content.get("a") should be(Some(update))
  }

  test("Modify will update the cache in cached mode") {
    Given("A pre-filled entityCache")
    val names = Set("a", "b", "c")
    val update = TestApp("a", Timestamp.now())
    content ++= names.map(t => t -> TestApp(t))
    entityCache.preDriverStarts.futureValue

    When("Modify an entity")
    val a = entityCache.modify("a")(_ => update).futureValue

    Then("The value is returned and the cache is updated")
    a should be(update)
    entityCache.cacheOpt.get("a") should be(Some(update))
    And("the content will be updated")
    content.get("a") should be(Some(update))
  }

  test("Expunge works in direct mode") {
    Given("A store with an entry")
    val names = Set("a", "b", "c")
    content ++= names.map(t => t -> TestApp(t))

    When("Expunge an entity")
    val result = entityCache.expunge("a").futureValue

    Then("The value is expunged")
    result should be(true)
    content.keys should be(Set("b", "c"))
  }

  test("Expunge will also clear the cache in cached mode") {
    Given("A pre-filled entityCache")
    val names = Set("a", "b", "c")
    content ++= names.map(t => t -> TestApp(t))
    entityCache.preDriverStarts.futureValue

    When("Expunge an entity")
    val result = entityCache.expunge("a").futureValue

    Then("The value is expunged")
    result should be(true)
    entityCache.cacheOpt.get should not be empty
    entityCache.cacheOpt.get.get("a") should be(None)
    And("content should not contain a anymore")
    content.get("a") should be(empty)
  }

  test("Names will list all entries (versioned and unversioned) in cached mode") {
    Given("A pre-filled entityCache")
    val names = Set("a", "b", "c")
    val now = Timestamp.now()
    content ++= (names.map(t => t -> TestApp(t)) ++ names.map(t => s"$t:$now" -> TestApp(t, now)))
    content should have size 6
    entityCache.preDriverStarts.futureValue

    When("Get all names in the cache")
    val allNames = entityCache.names().futureValue

    Then("All names are returned")
    allNames should have size 6
    entityCache.cacheOpt.get should have size 6
    entityCache.cacheOpt.get.values.flatten should have size 3 // linter:ignore:AvoidOptionMethod
  }

  test("Names will list all entries (versioned and unversioned) in direct mode") {
    Given("A store with three entries")
    val names = Set("a", "b", "c")
    val now = Timestamp.now()
    content ++= (names.map(t => t -> TestApp(t)) ++ names.map(t => s"$t:$now" -> TestApp(t, now)))
    content should have size 6

    When("Get all names in the cache")
    val allNames = entityCache.names().futureValue

    Then("All names are returned")
    allNames should have size 6
  }

  var content: mutable.Map[String, TestApp] = _
  var store: EntityStore[TestApp] = _
  var entityCache: EntityStoreCache[TestApp] = _

  before {
    content = mutable.Map.empty
    store = new TestStore[TestApp](content)
    entityCache = new EntityStoreCache(store)
    entityCache.markOpen()
  }

  case class TestApp(id: String, version: Timestamp = Timestamp.zero) extends MarathonState[Protos.MarathonApp, TestApp] {
    override def mergeFromProto(message: MarathonApp): TestApp = ???
    override def mergeFromProto(bytes: Array[Byte]): TestApp = ???
    override def toProto: MarathonApp = ???
  }
  class TestStore[T](map: mutable.Map[String, T]) extends EntityStore[T] {
    override def fetch(key: String): Future[Option[T]] = Future.successful(map.get(key))
    override def modify(key: String, onSuccess: (T) => Unit)(update: Update): Future[T] = {
      val updated = update(() => map(key))
      map += key -> updated
      onSuccess(updated)
      Future.successful(updated)
    }
    override def names(): Future[Seq[String]] = Future.successful(map.keys.toIndexedSeq)
    override def expunge(key: String, onSuccess: () => Unit): Future[Boolean] = {
      map -= key
      onSuccess()
      Future.successful(true)
    }
  }
}