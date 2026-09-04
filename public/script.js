const header = document.querySelector(".site-header");
const menuToggle = document.querySelector(".menu-toggle");
const navLinks = document.querySelectorAll(".nav-links a");
const homeLinks = document.querySelectorAll('a[href="#home"]');
const sectionIds = ["home", "about", "services", "process", "why-us", "contact"];
const details = document.querySelectorAll(".accordion details");
const form = document.querySelector("#enquiryForm");
const status = document.querySelector(".form-status");
const submissionModal = document.querySelector("#submissionModal");
const closeSubmissionModal = document.querySelector("#closeSubmissionModal");
const continueWhatsApp = document.querySelector("#continueWhatsApp");
const whatsappFallback = document.querySelector("#whatsappFallback");
const submissionMessage = document.querySelector("#submissionMessage");

let whatsappBusinessNumber = "";
const MAX_WHATSAPP_DESCRIPTION_LENGTH = 4000;
let whatsappUrl = "";
let previouslyFocusedElement = null;

const publicSettingsReady = fetch("/api/public-settings")
  .then((response) => response.ok ? response.json() : {})
  .then(({ whatsappBusinessNumber: number }) => { whatsappBusinessNumber = /^\d{8,15}$/.test(number || "") ? number : ""; })
  .catch(() => { whatsappBusinessNumber = ""; });

if ("scrollRestoration" in history) history.scrollRestoration = "manual";
window.addEventListener("load", () => {
  if (window.location.hash) history.replaceState(null, "", `${window.location.pathname}${window.location.search}`);
  window.scrollTo(0, 0);
});

const updateHeader = () => {
  header.classList.toggle("scrolled", window.scrollY > 24);
};

const updateActiveNav = () => {
  const activeId = sectionIds.find((id) => {
    const section = document.getElementById(id);
    if (!section) return id === "home";
    const rect = section.getBoundingClientRect();
    return rect.top <= window.innerHeight * 0.38 && rect.bottom >= window.innerHeight * 0.38;
  }) || "home";

  navLinks.forEach((link) => {
    const href = link.getAttribute("href") || "";
    const isActive = href === `#${activeId}`;
    link.classList.toggle("active", isActive);
    if (isActive) {
      link.setAttribute("aria-current", "page");
    } else {
      link.removeAttribute("aria-current");
    }
  });
};

updateHeader();
updateActiveNav();
window.addEventListener("scroll", () => {
  updateHeader();
  updateActiveNav();
}, { passive: true });

menuToggle.addEventListener("click", () => {
  const open = document.body.classList.toggle("menu-open");
  menuToggle.setAttribute("aria-expanded", String(open));
  menuToggle.setAttribute("aria-label", open ? "Close menu" : "Open menu");
});

navLinks.forEach((link) => {
  link.addEventListener("click", () => {
    document.body.classList.remove("menu-open");
    menuToggle.setAttribute("aria-expanded", "false");
    menuToggle.setAttribute("aria-label", "Open menu");
  });
});

homeLinks.forEach((link) => {
  link.addEventListener("click", (event) => {
    event.preventDefault();
    history.replaceState(null, "", `${window.location.pathname}${window.location.search}`);
    window.scrollTo({ top: 0, behavior: "smooth" });
    document.body.classList.remove("menu-open");
    menuToggle.setAttribute("aria-expanded", "false");
    menuToggle.setAttribute("aria-label", "Open menu");
  });
});

details.forEach((item) => {
  item.addEventListener("toggle", () => {
    if (!item.open) return;
    details.forEach((other) => {
      if (other !== item) other.removeAttribute("open");
    });
  });
});

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  status.textContent = "";

  if (!form.checkValidity()) {
    form.reportValidity();
    status.textContent = "Please complete the required project details.";
    return;
  }

  const submitButton = form.querySelector('button[type="submit"]');
  const enquiry = Object.fromEntries(new FormData(form).entries());
  submitButton.disabled = true;
  submitButton.classList.add("is-loading");
  submitButton.textContent = "Submitting your enquiry...";

  let stored = false;
  try {
    if (["http:", "https:"].includes(window.location.protocol)) {
      const response = await fetch("/api/enquiries", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
        body: new URLSearchParams(new FormData(form)),
      });
      const contentType = response.headers.get("content-type") || "";
      const result = contentType.includes("application/json") ? await response.json() : {};
      if (response.status === 400) throw new Error(result.message || "Please check your enquiry details and try again.");
      stored = response.ok && result.stored !== false;
    }
  } catch (error) {
    if (!(error instanceof TypeError)) {
      status.textContent = error.message || "We couldn't send your enquiry. Please try again.";
      return;
    }
  } finally {
    submitButton.disabled = false;
    submitButton.classList.remove("is-loading");
    submitButton.textContent = "Send Project Enquiry";
  }

  await publicSettingsReady;
  whatsappUrl = buildWhatsAppUrl(enquiry);
  submissionMessage.textContent = stored
    ? "Thank you for sharing your project requirements. We will review the details and follow up with you."
    : "Your details have not been stored on this website. Please use the enquiry form to share your project requirements and we will follow up directly.";
  status.textContent = "";
  whatsappFallback.hidden = true;
  previouslyFocusedElement = submitButton;
  submissionModal.classList.add("is-visible");
  submissionModal.setAttribute("aria-hidden", "false");
  continueWhatsApp.focus();
});

const cleanMessageValue = (value, maximumLength = 180) => String(value || "")
  .replace(/\r\n?/g, "\n")
  .replace(/[\u0000-\u001F\u007F]/g, " ")
  .replace(/\s+/g, " ")
  .trim()
  .slice(0, maximumLength);

const buildWhatsAppUrl = (enquiry) => {
  if (!/^\d{8,15}$/.test(whatsappBusinessNumber)) return "";

  const name = cleanMessageValue(enquiry.name, 120);
  const phone = cleanMessageValue(enquiry.phone, 40);
  const email = cleanMessageValue(enquiry.email, 160);
  const projectType = cleanMessageValue(enquiry.projectType, 100);
  const location = cleanMessageValue(enquiry.location, 180);
  const budget = cleanMessageValue(enquiry.budget, 80);
  const timeline = cleanMessageValue(enquiry.timeline, 80);
  const description = cleanMessageValue(enquiry.description, MAX_WHATSAPP_DESCRIPTION_LENGTH);

  const lines = [
    "Hello NEXSTRUCT,",
    "",
    "I would like to enquire about a project.",
    "",
    "*Customer Details*",
    `Name: ${name}`,
    `Phone: ${phone}`,
    ...(email ? [`Email: ${email}`] : []),
    "",
    "*Project Details*",
    `Project Type: ${projectType}`,
    ...(location ? [`Location: ${location}`] : []),
    ...(budget ? [`Budget: ${budget}`] : []),
    ...(timeline ? [`Preferred Timeline: ${timeline}`] : []),
    "",
    "*Requirement*",
    description,
    "",
    "I would like to discuss my project with your team.",
    "",
    "Thank you.",
    "NEXSTRUCT",
  ];

  return `https://wa.me/${whatsappBusinessNumber}?text=${encodeURIComponent(lines.join("\n"))}`;
};

continueWhatsApp.addEventListener("click", () => {
  if (!whatsappUrl) {
    whatsappFallback.hidden = false;
    return;
  }

  const whatsappWindow = window.open(whatsappUrl, "_blank");
  if (!whatsappWindow) {
    whatsappFallback.hidden = false;
    return;
  }
  whatsappWindow.opener = null;
});

const dismissSubmissionModal = () => {
  submissionModal.classList.remove("is-visible");
  submissionModal.setAttribute("aria-hidden", "true");
  whatsappFallback.hidden = true;
  previouslyFocusedElement?.focus();
};

closeSubmissionModal.addEventListener("click", dismissSubmissionModal);
submissionModal.addEventListener("click", (event) => {
  if (event.target === submissionModal) dismissSubmissionModal();
});

document.addEventListener("keydown", (event) => {
  if (event.key === "Escape" && submissionModal.classList.contains("is-visible")) dismissSubmissionModal();
});

const observer = new IntersectionObserver(
  (entries) => {
    entries.forEach((entry) => {
      if (entry.isIntersecting) {
        entry.target.classList.add("visible");
        observer.unobserve(entry.target);
      }
    });
  },
  { threshold: 0.12 }
);

document.querySelectorAll(".reveal, .roadmap").forEach((element) => observer.observe(element));
