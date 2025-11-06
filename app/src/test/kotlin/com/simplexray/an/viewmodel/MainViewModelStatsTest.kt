package com.simplexray.an.viewmodel

import com.simplexray.an.common.CoreStatsClient
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.viewmodel.CoreStatsState
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import android.app.Application
import com.xray.app.stats.command.SysStatsResponse
import com.simplexray.an.viewmodel.TrafficState

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelStatsTest {
    
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScope: TestScope
    private lateinit var mockApplication: Application
    private lateinit var mockPrefs: Preferences
    private lateinit var mockCoreStatsClient: CoreStatsClient
    
    @Before
    fun setup() {
        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        mockApplication = mockk<Application>(relaxed = true)
        mockPrefs = mockk<Preferences>(relaxed = true)
        mockCoreStatsClient = mockk<CoreStatsClient>(relaxed = true)
        
        every { mockPrefs.apiPort } returns 10085
    }
    
    @After
    fun tearDown() {
        clearAllMocks()
    }
    
    @Test
    fun `getStatsUpdateInterval should return default interval`() = testScope.runTest {
        val viewModel = MainViewModel(mockApplication)
        
        val interval = viewModel.getStatsUpdateInterval()
        
        assertEquals(1000L, interval) // Default 1 second
    }
    
    @Test
    fun `setStatsUpdateInterval should update interval`() = testScope.runTest {
        val viewModel = MainViewModel(mockApplication)
        
        viewModel.setStatsUpdateInterval(2000L)
        val interval = viewModel.getStatsUpdateInterval()
        
        assertEquals(2000L, interval)
    }
    
    @Test
    fun `setStatsUpdateInterval should enforce minimum interval`() = testScope.runTest {
        val viewModel = MainViewModel(mockApplication)
        
        viewModel.setStatsUpdateInterval(100L) // Below minimum
        val interval = viewModel.getStatsUpdateInterval()
        
        assertEquals(500L, interval) // Minimum 500ms
    }
    
    @Test
    fun `statsErrorState should be null initially`() = testScope.runTest {
        val viewModel = MainViewModel(mockApplication)
        
        val error = viewModel.statsErrorState.value
        
        assertNull(error)
    }
    
    @Test
    fun `isStatsRefreshing should be false initially`() = testScope.runTest {
        val viewModel = MainViewModel(mockApplication)
        
        val isRefreshing = viewModel.isStatsRefreshing.value
        
        assertFalse(isRefreshing)
    }
}

