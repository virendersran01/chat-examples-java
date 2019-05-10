package resourcecenterdemo.fragments;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import com.pubnub.api.PubNub;
import com.pubnub.api.callbacks.PNCallback;
import com.pubnub.api.callbacks.SubscribeCallback;
import com.pubnub.api.enums.PNOperationType;
import com.pubnub.api.models.consumer.PNPublishResult;
import com.pubnub.api.models.consumer.PNStatus;
import com.pubnub.api.models.consumer.presence.PNHereNowResult;
import com.pubnub.api.models.consumer.pubsub.PNMessageResult;
import com.pubnub.api.models.consumer.pubsub.PNPresenceEventResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import butterknife.BindView;
import resourcecenterdemo.R;
import resourcecenterdemo.adapters.ChatAdapter;
import resourcecenterdemo.pubnub.History;
import resourcecenterdemo.pubnub.Message;
import resourcecenterdemo.util.Helper;
import resourcecenterdemo.view.EmptyView;
import resourcecenterdemo.view.MessageComposer;

public class ChatFragment extends ParentFragment implements MessageComposer.Listener {

    private static final String ARGS_CHANNEL = "ARGS_CHANNEL";

    @BindView(R.id.chat_swipe)
    SwipeRefreshLayout mSwipeRefreshLayout;

    @BindView(R.id.chat_recycler_view)
    // tag::HIS-4.1[]
            RecyclerView mChatsRecyclerView;
    // tag::HIS-4.1[]

    @BindView(R.id.chats_message_composer)
    MessageComposer mMessageComposer;

    @BindView(R.id.chat_empty_view)
    EmptyView mEmptyView;

    // tag::HIS-4.2[]
    private ChatAdapter mChatAdapter;
    private List<Message> mMessages = new ArrayList<>();
    // end::HIS-4.2[]

    private String mChannel;
    private SubscribeCallback mPubNubListener;

    private RecyclerView.OnScrollListener mOnScrollListener;

    public static ChatFragment newInstance(String channel) {
        Bundle args = new Bundle();
        args.putString(ARGS_CHANNEL, channel);
        ChatFragment fragment = new ChatFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public int provideLayoutResourceId() {
        return R.layout.fragment_chat;
    }

    @Override
    public void setViewBehaviour(boolean viewFromCache) {
        if (!viewFromCache) {
            setHasOptionsMenu(true);
            initializeScrollListener();
            prepareRecyclerView();
            mSwipeRefreshLayout.setRefreshing(true);
            subscribe();
        }
    }

    private void prepareRecyclerView() {

        mSwipeRefreshLayout.setColorSchemeColors(getResources().getColor(R.color.colorPrimary));
        mSwipeRefreshLayout.setOnRefreshListener(this::fetchHistory);

        LinearLayoutManager linearLayoutManager = new LinearLayoutManager(fragmentContext);
        linearLayoutManager.setReverseLayout(false);
        linearLayoutManager.setStackFromEnd(true);
        mChatsRecyclerView.setLayoutManager(linearLayoutManager);

        DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(fragmentContext, RecyclerView.VERTICAL);
        dividerItemDecoration.setDrawable(getResources().getDrawable(R.drawable.chats_divider));
        mChatsRecyclerView.addItemDecoration(dividerItemDecoration);

        mChatsRecyclerView.setItemAnimator(new DefaultItemAnimator());

        mChatAdapter = new ChatAdapter(mChannel);
        mChatsRecyclerView.setAdapter(mChatAdapter);

        mMessageComposer.setListener(this);

        mChatsRecyclerView.addOnScrollListener(mOnScrollListener);
    }

    private void initializeScrollListener() {
        mOnScrollListener = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                int firstCompletelyVisibleItemPosition =
                        ((LinearLayoutManager) recyclerView.getLayoutManager()).findFirstCompletelyVisibleItemPosition();

                if (firstCompletelyVisibleItemPosition == History.TOP_ITEM_OFFSET && dy < 0) {
                    fetchHistory();
                }
            }
        };
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_chat, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_chat_info:
                hostActivity.addFragment(ChatInfoFragment.newInstance(mChannel));
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public String setScreenTitle() {
        hostActivity.enableBackButton(false);
        scrollChatToBottom();
        loadCurrentOccupancy();
        return mChannel;
    }

    @Override
    public void extractArguments() {
        super.extractArguments();
        mChannel = getArguments().getString(ARGS_CHANNEL);
    }

    @Override
    public void onReady() {
        initListener();

        // tag::FRG-2[]
        // tag::ignore[]
        /*
        // end::ignore[]
        hostActivity.getPubNub();
        // tag::ignore[]
        */
        // end::ignore[]
        // end::FRG-2[]
    }

    // tag::SUB-2[]
    private void initListener() {
        mPubNubListener = new SubscribeCallback() {
            @Override
            public void status(PubNub pubnub, PNStatus status) {
                if (status.getOperation() == PNOperationType.PNSubscribeOperation && status.getAffectedChannels()
                        .contains(mChannel)) {
                    mSwipeRefreshLayout.setRefreshing(false);
                    fetchHistory();
                }
            }

            @Override
            public void message(PubNub pubnub, PNMessageResult message) {
                handleNewMessage(message);
            }

            @Override
            public void presence(PubNub pubnub, PNPresenceEventResult presence) {
                if (presence.getChannel().equals(mChannel)) {
                    runOnUiThread(() -> hostActivity.setSubtitle(fragmentContext.getResources()
                            .getString(R.string.members_online, presence.getOccupancy())));
                }
            }
        };
    }
    // end::SUB-2[]

    private void loadCurrentOccupancy() {
        hostActivity.getPubNub()
                .hereNow()
                .channels(Arrays.asList(mChannel))
                .async(new PNCallback<PNHereNowResult>() {
                    @Override
                    public void onResponse(PNHereNowResult result, PNStatus status) {
                        if (!status.isError()) {
                            hostActivity.setSubtitle(fragmentContext.getResources()
                                    .getString(R.string.members_online, result.getTotalOccupancy()));
                        }
                    }
                });
    }

    private void handleNewMessage(PNMessageResult message) {
        if (message.getChannel().equals(mChannel)) {
            Message msg = Message.serialize(message);
            mMessages.add(msg);
            History.chainMessages(mMessages, mMessages.size());
            runOnUiThread(() -> {
                if (mEmptyView.getVisibility() == View.VISIBLE) {
                    mEmptyView.setVisibility(View.GONE);
                }
                mChatAdapter.update(mMessages);
                scrollChatToBottom();
            });
        }
    }

    // tag::SUB-1[]
    private void subscribe() {
        hostActivity.getPubNub()
                .subscribe()
                .channels(Collections.singletonList(mChannel))
                .withPresence()
                .execute();
    }
    // end::SUB-1[]

    // tag::HIS-1[]
    private void fetchHistory() {
        if (History.isLoading()) {
            return;
        }
        History.setLoading(true);
        mSwipeRefreshLayout.setRefreshing(true);
        History.getAllMessages(hostActivity.getPubNub(), mChannel, getEarliestTimestamp(),
                new History.CallbackSkeleton() {
                    @Override
                    public void handleResponse(List<Message> newMessages) {
                        if (!newMessages.isEmpty()) {
                            mMessages.addAll(0, newMessages);
                            History.chainMessages(mMessages, mMessages.size());
                            runOnUiThread(() -> mChatAdapter.update(mMessages));
                        } else if (mMessages.isEmpty()) {
                            runOnUiThread(() -> mEmptyView.setVisibility(View.VISIBLE));
                        } else {
                            runOnUiThread(() -> Toast.makeText(fragmentContext, getString(R.string.no_more_messages),
                                    Toast.LENGTH_SHORT).show());
                        }
                        runOnUiThread(() -> {
                            mSwipeRefreshLayout.setRefreshing(false);
                            Log.d("new_arrival", "size: " + mMessages.size());
                        });
                        History.setLoading(false);
                    }
                });
    }
    // end::HIS-1[]

    private Long getEarliestTimestamp() {
        if (mMessages != null && !mMessages.isEmpty()) {
            return mMessages.get(0).getTimetoken();
        }
        return null;
    }

    @Override
    public void onDestroy() {
        hostActivity.getPubNub().removeListener(mPubNubListener);
        super.onDestroy();
    }

    // tag::SEND-2[]
    @Override
    public void onSentClick(String message) {
        // tag::ignore[]
        if (TextUtils.isEmpty(message)) {
            StringBuilder messageBuilder = new StringBuilder("");
            messageBuilder.append(UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            messageBuilder.append("\n");
            messageBuilder.append(Helper.parseDateTime(System.currentTimeMillis()));
            message = messageBuilder.toString();
        }
        // end::ignore[]
        hostActivity.getPubNub()
                .publish()
                .channel(mChannel)
                .shouldStore(true)
                .message(Message.newBuilder().text(message).build())
                .async(new PNCallback<PNPublishResult>() {
                    @Override
                    public void onResponse(PNPublishResult result, PNStatus status) {

                    }
                });
    }
    // end::SEND-2[]

    private void scrollChatToBottom() {
        mChatsRecyclerView.scrollToPosition(mMessages.size() - 1);
    }

    @Override
    public SubscribeCallback provideListener() {
        return mPubNubListener;
    }
}
