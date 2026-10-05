/** What the room looks like before the table is ready, or when it cannot be reached. */
export function Loading({ message, failed = false }: { message: string; failed?: boolean }) {
  return (
    <div className="m-auto flex flex-col items-center gap-6 text-center">
      <img
        src="/logo-192.png"
        alt=""
        width={84}
        height={84}
        className="h-21 w-21 drop-shadow-[0_12px_30px_rgba(0,0,0,0.5)]"
      />
      <p className="text-xl font-semibold tracking-[0.42em] text-ivory">BANCA</p>
      {failed ? (
        <span aria-hidden className="h-1.75 w-10 rounded-full bg-white/15" />
      ) : (
        <div aria-hidden className="flex gap-2.5">
          <span className="loading-dot" />
          <span className="loading-dot" />
          <span className="loading-dot" />
        </div>
      )}
      <p role="status" className="label">
        {message}
      </p>
    </div>
  )
}
