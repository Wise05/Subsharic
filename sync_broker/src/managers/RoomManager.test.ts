import assert from 'node:assert/strict';
import test from 'node:test';
import WebSocket from 'ws';
import { RoomManager } from './RoomManager';

function fakeClient(clientId: string) {
  const messages: Record<string, unknown>[] = [];
  const socket = {
    readyState: WebSocket.OPEN,
    send: (message: string) => messages.push(JSON.parse(message) as Record<string, unknown>),
  } as unknown as WebSocket;
  return { client: { clientId, socket }, messages };
}

function roomIdFrom(messages: Record<string, unknown>[]): string {
  const created = messages.find((message) => message.type === 'ROOM_CREATED');
  assert.ok(created);
  return created.roomId as string;
}

test('host disconnect elects the longest-connected remaining room member', () => {
  let now = 10;
  const manager = new RoomManager(() => now++);
  const oldest = fakeClient('oldest');
  const next = fakeClient('next');
  const newest = fakeClient('newest');

  manager.handle(oldest.client, { type: 'CREATE_ROOM' });
  const roomId = roomIdFrom(oldest.messages);
  manager.handle(next.client, { type: 'JOIN_ROOM', roomId });
  manager.handle(newest.client, { type: 'JOIN_ROOM', roomId });

  manager.disconnect('oldest');
  const election = next.messages.filter((message) => message.type === 'MEMBER_LEFT').at(-1);
  assert.equal(election?.hostId, 'next');

  manager.disconnect('next');
  const secondElection = newest.messages.filter((message) => message.type === 'MEMBER_LEFT').at(-1);
  assert.equal(secondElection?.hostId, 'newest');
});

test('non-host departure preserves host and broker stamps playback state', () => {
  let now = 500;
  const manager = new RoomManager(() => now++);
  const host = fakeClient('host');
  const member = fakeClient('member');
  manager.handle(host.client, { type: 'CREATE_ROOM' });
  const roomId = roomIdFrom(host.messages);
  manager.handle(member.client, { type: 'JOIN_ROOM', roomId });

  manager.handle(host.client, {
    type: 'BROADCAST_STATE',
    state: {
      track: { id: 'track-1', title: 'Track' },
      isPlaying: true,
      positionMs: 20_000,
      updatedAt: 1, // Client timestamp is replaced by the broker's time.
    },
  });

  const update = member.messages.find((message) => message.type === 'STATE_UPDATED');
  assert.ok(update);
  assert.equal((update.state as { updatedAt: number }).updatedAt, 502);
  assert.equal(host.messages.some((message) => message.type === 'STATE_UPDATED'), true);

  manager.disconnect('member');
  const memberLeft = host.messages.find((message) => message.type === 'MEMBER_LEFT');
  assert.equal(memberLeft?.hostId, 'host');
});

test('members may append duplicate tracks; only the host may reorder or remove them', () => {
  let now = 100;
  const manager = new RoomManager(() => now++);
  const host = fakeClient('host');
  const member = fakeClient('member');
  manager.handle(host.client, { type: 'CREATE_ROOM' });
  const roomId = roomIdFrom(host.messages);
  manager.handle(member.client, { type: 'JOIN_ROOM', roomId });
  const track = { id: 'same-track', title: 'Same Track', artist: 'Artist' };

  manager.handle(member.client, { type: 'ADD_TO_QUEUE', track });
  manager.handle(member.client, { type: 'ADD_TO_QUEUE', track });
  const addedQueue = host.messages.filter((message) => message.type === 'QUEUE_UPDATED').at(-1)?.queue as
    { queueId: string; track: { id: string } }[];
  assert.equal(addedQueue.length, 2);
  assert.notEqual(addedQueue[0].queueId, addedQueue[1].queueId);
  assert.equal(addedQueue[0].track.id, 'same-track');

  manager.handle(member.client, {
    type: 'BROADCAST_STATE',
    state: { track, isPlaying: true, positionMs: 0, updatedAt: 0 },
  });
  assert.equal(member.messages.at(-1)?.code, 'NOT_HOST');

  manager.handle(member.client, {
    type: 'REORDER_QUEUE',
    queueIds: [addedQueue[1].queueId, addedQueue[0].queueId],
  });
  assert.equal(member.messages.at(-1)?.code, 'NOT_HOST');

  manager.handle(host.client, {
    type: 'REORDER_QUEUE',
    queueIds: [addedQueue[1].queueId, addedQueue[0].queueId],
  });
  const reorderedQueue = member.messages.filter((message) => message.type === 'QUEUE_UPDATED').at(-1)?.queue as
    { queueId: string }[];
  assert.equal(reorderedQueue[0].queueId, addedQueue[1].queueId);

  manager.handle(host.client, { type: 'REMOVE_FROM_QUEUE', queueId: addedQueue[1].queueId });
  const remainingQueue = member.messages.filter((message) => message.type === 'QUEUE_UPDATED').at(-1)?.queue as
    { queueId: string }[];
  assert.equal(remainingQueue.length, 1);
  assert.equal(remainingQueue[0].queueId, addedQueue[0].queueId);
});
