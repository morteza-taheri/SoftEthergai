package vn.unlimit.vpngate.ui.screens.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.activities.DetailActivity
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.models.VPNGateConnectionList
import vn.unlimit.vpngate.provider.BaseProvider
import vn.unlimit.vpngate.ui.components.FullScreenError
import vn.unlimit.vpngate.ui.components.FullScreenLoading
import vn.unlimit.vpngate.ui.components.FullScreenNoNetwork
import vn.unlimit.vpngate.ui.components.ServerCard
import vn.unlimit.vpngate.utils.DataUtil
import vn.unlimit.vpngate.viewmodels.ConnectionListViewModel

/**
 * Home: the redesigned server list. Holds the search/sort/filter state that
 * used to live in MainActivity's toolbar + HomeFragment and renders the
 * result in a LazyColumn of ServerCards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    connectionListViewModel: ConnectionListViewModel,
    onOpenStatus: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as App
    val dataUtil = remember { app.dataUtil!! }
    val scope = rememberCoroutineScope()

    // ----- State (formerly MainActivity menu state + HomeFragment fields)
    var list by remember { mutableStateOf<VPNGateConnectionList?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isError by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }
    var sortProperty by remember { mutableStateOf(dataUtil.getStringSetting(SORT_PROPERTY_KEY, "") ?: "") }
    var sortType by remember { mutableStateOf(dataUtil.getIntSetting(SORT_TYPE_KEY, VPNGateConnectionList.ORDER.ASC)) }
    var activeFilter by remember { mutableStateOf<VPNGateConnectionList.Filter?>(null) }
    var emptyMessageRes by remember { mutableStateOf<Int?>(null) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }
    var copyTarget by remember { mutableStateOf<VPNGateConnection?>(null) }
    var debugTarget by remember { mutableStateOf<VPNGateConnection?>(null) }
    var noNetwork by remember { mutableStateOf(false) }
    var contentVisible by remember { mutableStateOf(false) }

    // ----- Data helpers (same threading model as HomeFragment)
    fun applyView(listModel: VPNGateConnectionList?, emptyRes: Int?) {
        list = listModel
        emptyMessageRes = emptyRes
    }

    fun refreshView() {
        scope.launch(Dispatchers.IO) {
            val base = connectionListViewModel.vpnGateConnectionList.value
            var result = base?.advancedFilter(activeFilter)
            if (isSearching && keyword.isNotEmpty()) {
                result = result?.filter(keyword)
            }
            if (result != null && sortProperty.isNotEmpty()) {
                result.sort(sortProperty, sortType)
            }
            val size = result?.size() ?: 0
            val emptyRes = when {
                size == 0 && (isSearching && keyword.isNotEmpty()) -> R.string.empty_search_result
                size == 0 && activeFilter != null -> R.string.empty_filter_result
                size == 0 -> R.string.no_server_available
                else -> null
            }
            withContext(Dispatchers.Main) {
                applyView(result, emptyRes)
            }
        }
    }

    fun search(query: String) {
        keyword = query
        isSearching = query.isNotEmpty()
        refreshView()
    }

    // ----- Observers (same as old MainActivity + HomeFragment)
    val isLoadingVm by connectionListViewModel.isLoading.observeAsState(false)
    val isErrorVm by connectionListViewModel.isError.observeAsState(false)
    LaunchedEffect(isLoadingVm) {
        isLoading = isLoadingVm
        if (!isLoadingVm) {
            val value = connectionListViewModel.vpnGateConnectionList.value
            if (value != null && value.size() > 0) {
                isError = false
                noNetwork = false
                contentVisible = true
                refreshView()
            }
        }
    }
    LaunchedEffect(isErrorVm) {
        if (isErrorVm) {
            isError = true
            contentVisible = false
        }
    }
    // Initial load: cache → display; network state → loading/error/no-network
    LaunchedEffect(Unit) {
        val cached = withContext(Dispatchers.IO) { dataUtil.connectionsCache }
        val online = withContext(Dispatchers.IO) { DataUtil.isOnline(context.applicationContext) }
        when {
            cached != null && cached.size() > 0 -> {
                contentVisible = true
                refreshView()
            }
            online -> connectionListViewModel.getAPIData()
            else -> {
                noNetwork = true
                contentVisible = false
            }
        }
    }
    // A successful API load also flips content visible via the isLoading observer.

    // ----- Render
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isError -> FullScreenError(onRetry = {
                isError = false
                connectionListViewModel.getAPIData()
            })
            noNetwork -> FullScreenNoNetwork()
            !contentVisible || (isLoading && (list == null || list!!.size() == 0)) -> FullScreenLoading()
            else -> {
                val listState = rememberLazyListState()
                var showToTop by remember { mutableStateOf(false) }
                LaunchedEffect(listState) {
                    snapshotFlow {
                        val info = listState.layoutInfo
                        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                        lastVisible > 0 && listState.firstVisibleItemIndex > 4
                    }.distinctUntilChanged().collect { showToTop = it }
                }
                BackHandler(enabled = isSearching) {
                    isSearching = false
                    keyword = ""
                    refreshView()
                }
                Scaffold(
                    topBar = {
                        if (isSearching) {
                            OutlinedTextField(
                                value = keyword,
                                onValueChange = { search(it) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                placeholder = { Text(stringResource(R.string.search_hint)) },
                                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                trailingIcon = {
                                    IconButton(onClick = {
                                        isSearching = false
                                        keyword = ""
                                        refreshView()
                                    }) {
                                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
                                    }
                                },
                                singleLine = true,
                            )
                        } else {
                            TopAppBar(
                                title = { Text(stringResource(R.string.app_name)) },
                                actions = {
                                    IconButton(onClick = { isSearching = true }) {
                                        Icon(
                                            Icons.Filled.Search,
                                            contentDescription = stringResource(R.string.search),
                                        )
                                    }
                                    IconButton(onClick = { showSortSheet = true }) {
                                        Icon(
                                            Icons.Filled.Sort,
                                            contentDescription = stringResource(R.string.sort),
                                        )
                                    }
                                    IconButton(onClick = { showFilterSheet = true }) {
                                        Icon(
                                            Icons.Filled.FilterList,
                                            contentDescription = stringResource(R.string.filter),
                                            tint = if (activeFilter != null) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.background,
                                ),
                            )
                        }
                    },
                    floatingActionButton = {
                        AnimatedVisibility(visible = showToTop) {
                            ExtendedFloatingActionButton(
                                onClick = {
                                    scope.launch { listState.animateScrollToItem(0) }
                                },
                                icon = {
                                    Icon(
                                        Icons.Filled.VerticalAlignTop,
                                        contentDescription = stringResource(R.string.to_top),
                                    )
                                },
                                text = {},
                            )
                        }
                    },
                ) { padding ->
                    val model = list
                    if (model == null || model.size() == 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
                            contentAlignment = Alignment.Center,
                        ) {
                            emptyMessageRes?.let {
                                Text(
                                    if (it == R.string.empty_search_result) {
                                        stringResource(it, keyword)
                                    } else {
                                        stringResource(it)
                                    },
                                    modifier = Modifier.padding(24.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } ?: Text(
                                stringResource(R.string.no_server_available),
                                modifier = Modifier.padding(24.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        PullToRefreshBox(
                            isRefreshing = isLoading,
                            onRefresh = { connectionListViewModel.getAPIData() },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
                        ) {
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(
                                    start = 10.dp, end = 10.dp, top = 4.dp, bottom = 16.dp,
                                ),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(
                                    count = model.size(),
                                    key = { index ->
                                        model.get(index).calculateHostName + "#" +
                                                model.get(index).ip + "#" + index
                                    },
                                ) { index ->
                                    val conn = model.get(index)
                                    ServerCard(
                                        connection = conn,
                                        dataUtil = dataUtil,
                                        onClick = {
                                            try {
                                                val intent = Intent(context, DetailActivity::class.java)
                                                intent.putExtra(
                                                    BaseProvider.PASS_DETAIL_VPN_CONNECTION,
                                                    conn,
                                                )
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                        },
                                        onLongClick = { copyTarget = conn },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showFilterSheet) {
            FilterSheet(
                initial = activeFilter,
                onApply = { filter ->
                    activeFilter = filter
                    showFilterSheet = false
                    connectionListViewModel.vpnGateConnectionList.value?.filter = filter
                    refreshView()
                },
                onReset = {
                    activeFilter = null
                    showFilterSheet = false
                    connectionListViewModel.vpnGateConnectionList.value?.filter = null
                    refreshView()
                },
                onDismiss = { showFilterSheet = false },
            )
        }
        if (showSortSheet) {
            SortSheet(
                initialProperty = sortProperty,
                initialType = sortType,
                onApply = { property, type ->
                    sortProperty = property ?: ""
                    sortType = type
                    dataUtil.setStringSetting(SORT_PROPERTY_KEY, property)
                    dataUtil.setIntSetting(SORT_TYPE_KEY, type)
                    showSortSheet = false
                    refreshView()
                },
                onDismiss = { showSortSheet = false },
            )
        }
        copyTarget?.let { target ->
            CopySheet(
                connection = target,
                onCopyIp = {
                    copyToClipboard(context, target.ip ?: "")
                    copyTarget = null
                },
                onCopyHostname = {
                    copyToClipboard(context, target.calculateHostName)
                    copyTarget = null
                },
                onDebugInfo = {
                    copyTarget = null
                    debugTarget = target
                },
                onDismiss = { copyTarget = null },
            )
        }
        debugTarget?.let { target ->
            DebugSheet(
                hostname = target.calculateHostName,
                payload = connectionListViewModel.debugPayload(target),
                onDismiss = { debugTarget = null },
            )
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("text", text))
        Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private const val SORT_PROPERTY_KEY = "SORT_PROPERTY_KEY"
private const val SORT_TYPE_KEY = "SORT_TYPE_KEY"
