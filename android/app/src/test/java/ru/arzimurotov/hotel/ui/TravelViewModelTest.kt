package ru.arzimurotov.hotel.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import ru.arzimurotov.hotel.data.*
import ru.arzimurotov.hotel.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class TravelViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun before(){Dispatchers.setMain(dispatcher)}
    @After fun after(){Dispatchers.resetMain()}
    private class Fake:TravelRepository {
        var fails=false;var calls=0;var created=0;var slowUncancellable=false
        val b=Booking("id","HC-test","2026-12-01","2026-12-04",2,0,"PENDING_PAYMENT","ONLINE_DEMO","UNPAID",30000,"RUB",1000,2000,1,null,BookingSnapshot("Гостиница","Адрес","Клиент","x@y.test","STANDARD",listOf("101"),10000,30000,emptyList(),"Europe/Moscow"))
        override suspend fun search(q:StayRequest,offset:Int):SearchResponse {calls++;delay(if(q.name=="old")1000 else 10);if(fails)throw java.io.IOException();return SearchResponse(listOf(SearchHit(q.name,emptyList())),1,offset,20,1)}
        override suspend fun availability(id:String,q:StayRequest)=Availability(id,emptyList(),1)
        override suspend fun create(userId:String,r:CreateBooking):Booking {created++;delay(100);return b}
        override suspend fun bookings(offset:Int):Page<Booking> {if(slowUncancellable)withContext(NonCancellable){delay(100)};return Page(listOf(b),1,0,20)}
        override suspend fun booking(id:String)=b
        override suspend fun cancel(id:String)=b.copy(status="CANCELLED")
        override suspend fun pay(userId:String,id:String)=b.copy(status="CONFIRMED",paymentStatus="PAID")
        override suspend fun receipt(id:String)="%PDF-test".toByteArray()
    }
    @Test fun latestQueryWinsAndFailuresNeverInventResults()=runTest(dispatcher) {
        val r=Fake();val vm=TravelViewModel(r)
        vm.search(SearchQuery(name="old"));advanceTimeBy(400);vm.search(SearchQuery(name="new"));advanceUntilIdle()
        assertEquals("new",vm.state.value.search!!.items.single().hotelId)
        r.fails=true;vm.search(SearchQuery(name="failure"));advanceUntilIdle()
        assertNull(vm.state.value.search);assertNotNull(vm.state.value.searchError)
        r.fails=false;vm.search(SearchQuery(name="retry"));advanceUntilIdle();assertNull(vm.state.value.searchError)
    }
    @Test fun duplicateSubmitAccountIsolationAndPdf()=runTest(dispatcher) {
        val r=Fake();val vm=TravelViewModel(r)
        val request=CreateBooking("h","t","rate",SearchQuery().wire(),paymentMethod="ONLINE_DEMO",expectedTotal=1,idempotencyKey="")
        vm.create("u",request);vm.create("u",request);advanceUntilIdle();assertEquals(1,r.created)
        assertEquals("id",vm.state.value.created!!.id)
        vm.consumeCreated();vm.loadBookings();advanceUntilIdle()
        vm.pay("u","id");advanceUntilIdle();assertEquals("PAID",vm.state.value.bookings.single().paymentStatus)
        vm.receipt("receipt");advanceUntilIdle();assertNotNull(vm.state.value.pdf)
        vm.clearAccount();assertTrue(vm.state.value.bookings.isEmpty());assertNull(vm.state.value.pdf)
    }
    @Test fun logoutDuringResponseCannotRestoreOldPersonalOrders()=runTest(dispatcher) {
        val r=Fake();r.slowUncancellable=true;val vm=TravelViewModel(r)
        vm.loadBookings();runCurrent();vm.clearAccount();advanceUntilIdle()
        assertTrue(vm.state.value.bookings.isEmpty());assertFalse(vm.state.value.busy)
        r.slowUncancellable=false;vm.loadBookings();advanceUntilIdle()
        assertEquals(1,vm.state.value.bookings.size)
    }
}
