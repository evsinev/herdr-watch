import * as React from "react";
import { cn } from "@/lib/utils";

export interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  invalid?: boolean;
}

const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ className, invalid, ...props }, ref) => {
    return (
      <input
        ref={ref}
        className={cn(
          "h-9 w-full rounded-md bg-field px-3 font-mono text-[13px] text-ink",
          "border transition-colors placeholder:text-muted-3",
          "focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-accent focus-visible:border-accent",
          "disabled:cursor-not-allowed disabled:opacity-60",
          invalid ? "border-danger focus-visible:ring-danger focus-visible:border-danger" : "border-line-strong",
          className,
        )}
        {...props}
      />
    );
  },
);
Input.displayName = "Input";

export { Input };
