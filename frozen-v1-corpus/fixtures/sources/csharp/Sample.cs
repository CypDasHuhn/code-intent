using Microsoft.Extensions.Logging;
using System;
using System.Diagnostics;

public class Service
{
    private readonly ILogger<Service> _logger;

    // cint:bookkeeping
    private int skipped = 0;

    public Service(ILogger<Service> logger)
    {
        _logger = logger;
    }

    public int Run(string id)
    {
        _logger.LogInformation("run {Id}", id);
        if (string.IsNullOrEmpty(id))
        {
            return 0;
        }
        var value = Compute(id);
        if (value < 0)
        {
            _logger.LogError("negative {Id}", id);
            throw new InvalidOperationException("bad");
        }
        Console.WriteLine("done {0}", value);
        return value;
    }

    private int Compute(string id)
    {
        Debug.Assert(!string.IsNullOrEmpty(id));
        skipped++;
        return id.Length;
    }
}
